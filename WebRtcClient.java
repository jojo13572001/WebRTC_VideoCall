package fennec.messenger.watch.Utils;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import com.github.nkzawa.socketio.client.Socket;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

import fennec.messenger.watch.Manager.MessageManager;
import fennec.messenger.watch.Module.VideoCallMessage;

/**
 * Created by Bean on 2018/5/24.
 */

public class WebRtcClient {
    private final static String TAG = "WebRtcClient";

    private PeerConnectionFactory factory;
    private LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<>();
    private Socket client;
    private Map<String, Peer> peers = new HashMap<>();
    private MediaConstraints constraints = new MediaConstraints();
    private WebRtcListener webRtcListener;
    private Context mContext;
    private WebView mWebView;

    public WebRtcClient(Context ctx) {
        this.mContext= ctx;

        //Initialize PeerConnectionFactory globals.
        //Params are context, initAudio,initVideo and videoCodecHwAcceleration
        PeerConnectionFactory.initializeAndroidGlobals(ctx, true, true, true);
        constraints.optional.add(new MediaConstraints.KeyValuePair("RtpDataChannels", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        factory = new PeerConnectionFactory(new PeerConnectionFactory.Options());
        iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));
        iceServers.add(new PeerConnection.IceServer("stun:stun.services.mozilla.com"));
        iceServers.add(new PeerConnection.IceServer("turn:numb.viagenie.ca","jojo13572001@gmail.com", "abcd1234567"));
    }

    public ChildEventListener onVideoCallChildEventListener = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String prevChildKey) {
            System.out.println("The updated post title is: " + dataSnapshot.toString());
            VideoCallMessage vMsg = dataSnapshot.getValue(VideoCallMessage.class);

            if(vMsg.sender == getPeer("videoCall").mYourId) {
                if(!vMsg.ice.isEmpty()) {
                    try {
                        JSONObject obj = new JSONObject(vMsg.ice);
                        onReceiveCandidate("videoCall", obj);
                    } catch (Throwable tx) {
                        Log.e("[WebRTC]", "Could not parse malformed JSON: \"" + vMsg.ice + "\"");
                    }
                }
            }
            //read message
        }

        @Override
        public void onChildChanged(DataSnapshot dataSnapshot, String prevChildKey) {}

        @Override
        public void onChildRemoved(DataSnapshot dataSnapshot) {}

        @Override
        public void onChildMoved(DataSnapshot dataSnapshot, String prevChildKey) {}

        @Override
        public void onCancelled(DatabaseError databaseError) {}
    };

    public void sendDataMessageToAllPeer(String message) {
        for (Peer peer : peers.values()) {
            peer.sendDataChannelMessage(message);
        }
    }

    private Peer getPeer(String from) {
        Peer peer;
        if (!peers.containsKey(from)) {
            peer = addPeer(from);
        } else {
            peer = peers.get(from);
        }
        return peer;
    }

    public Peer addPeer(String id) {
        Peer peer = new Peer(id);
        peers.put(id, peer);
        return peer;
    }

    private void removePeer(String id) {
        Peer peer = peers.get(id);
        peer.release();
        peers.remove(peer.id);
    }

    public void onReceiveInit(String fromUid) {
        Log.d(TAG, "[WebRTC] onReceiveInit fromUid:" + fromUid);
        Peer peer = getPeer(fromUid);
        peer.pc.createOffer(peer, constraints);
    }

    public void onReceiveOffer(String fromUid, JSONObject payload) {
        Log.d(TAG, "onReceiveOffer uid:" + fromUid + " data:" + payload);
        try {
            Peer peer = getPeer(fromUid);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                    payload.getString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
            peer.pc.createAnswer(peer, constraints);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void onReceiveAnswer(String fromUid, JSONObject payload) {
        Log.d(TAG, "onReceiveAnswer uid:" + fromUid + " data:" + payload);
        try {
            Peer peer = getPeer(fromUid);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                    payload.getString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void onReceiveCandidate(String fromUid, JSONObject payload) {
        Log.d(TAG, "onReceiveCandidate uid:" + fromUid + " data:" + payload);
        try {
            Peer peer = getPeer(fromUid);
            //if (peer.pc.getRemoteDescription() != null) {
                IceCandidate candidate = new IceCandidate(
                        payload.getString("sdpMid"),
                        payload.getInt("sdpMLineIndex"),
                        payload.getString("sdp")
                );
                peer.pc.addIceCandidate(candidate);
            //}
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void release() {
        for (Peer peer : peers.values()) {
            peer.release();
        }
        factory.dispose();
        client.disconnect();
        client.close();
    }

    public void callJsFunction(String jFunction){
        mWebView.loadUrl("javascript:" + jFunction);
    }

    public class Peer implements SdpObserver, PeerConnection.Observer, DataChannel.Observer {
        PeerConnection pc;
        String id;
        DataChannel dc;
        Random rand = new Random();
        int mYourId = 0;


        public Peer(String id) {
            Log.d(TAG, "[WebRtcClient] new Peer: " + id);
            this.mYourId = rand.nextInt(65535) + 1;
            this.pc = factory.createPeerConnection(
                    iceServers, //ICE服务器列表
                    constraints, //MediaConstraints
                    this); //Context
            Log.d(TAG, "[WebRtcClient] pc: " + this.pc);
            this.id = id;

            /*
            DataChannel.Init 可配参数说明：
            ordered：是否保证顺序传输；
            maxRetransmitTimeMs：重传允许的最长时间；
            maxRetransmits：重传允许的最大次数；
             */
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = true;
            dc = pc.createDataChannel("dataChannel", init);
        }

        public void addStream(MediaStream stream){
            this.pc.addStream(stream);
        }

        public void sendDataChannelMessage(String message) {
            byte[] msg = message.getBytes();
            DataChannel.Buffer buffer = new DataChannel.Buffer(
                    ByteBuffer.wrap(msg),
                    false);
            dc.send(buffer);
        }

        public void release() {
            pc.dispose();
            dc.close();
            dc.dispose();
        }

        //SdpObserver-------------------------------------------------------------------------------

        @Override
        public void onCreateSuccess(SessionDescription sdp) {
            Log.d(TAG, "onCreateSuccess: " + sdp.description);
            try {
                JSONObject payload = new JSONObject();
                payload.put("type", sdp.type.canonicalForm());
                payload.put("sdp", sdp.description);
                //sendMessage(id, sdp.type.canonicalForm(), payload);
                pc.setLocalDescription(Peer.this, sdp);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSetSuccess() {

        }

        @Override
        public void onCreateFailure(String s) {

        }

        @Override
        public void onSetFailure(String s) {

        }

        //DataChannel.Observer----------------------------------------------------------------------

        @Override
        public void onBufferedAmountChange(long l) {

        }

        @Override
        public void onStateChange() {
            Log.d(TAG, "onDataChannel onStateChange:" + dc.state());
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            Log.d(TAG, "onDataChannel onMessage : " + buffer);
            ByteBuffer data = buffer.data;
            byte[] bytes = new byte[data.capacity()];
            data.get(bytes);
            String msg = new String(bytes);
            if (webRtcListener != null) {
                webRtcListener.onReceiveDataChannelMessage(msg);
            }
        }

        //PeerConnection.Observer-------------------------------------------------------------------

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {

        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(TAG, "onIceConnectionChange : " + iceConnectionState.name());
            if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                removePeer(id);
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            //try {
                if(candidate !=  null) {
                    //JSONObject payload = new JSONObject();
                    //payload.put("label", candidate.sdpMLineIndex);
                    //payload.put("id", candidate.sdpMid);
                    //payload.put("candidate", candidate.sdp);
                    VideoCallMessage message = new VideoCallMessage(mYourId, candidate);

                    MessageManager.getInstance().sendVideoCallMessage(message);
                    //callJsFunction("sendMessage()");

                    //mWebView.loadUrl("javascript:" + "sendMessage(test)");
                    //sendMessage(id, "candidate", payload);
                }
            //} catch (JSONException e) {
            //    e.printStackTrace();
            //}
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {

        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {

        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Log.d(TAG, "onDataChannel label:" + dataChannel.label());
            dataChannel.registerObserver(this);
        }

        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {

        }
    }

    public interface WebRtcListener {
        void onReceiveDataChannelMessage(String message);
    }
}