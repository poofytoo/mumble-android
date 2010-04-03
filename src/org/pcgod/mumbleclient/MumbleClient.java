package org.pcgod.mumbleclient;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import net.sf.mumble.MumbleProto.Authenticate;
import net.sf.mumble.MumbleProto.ServerSync;
import net.sf.mumble.MumbleProto.UserState;
import net.sf.mumble.MumbleProto.Version;

import org.pcgod.mumbleclient.jni.SWIGTYPE_p_CELTDecoder;
import org.pcgod.mumbleclient.jni.SWIGTYPE_p_CELTMode;
import org.pcgod.mumbleclient.jni.celt;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;

public class MumbleClient implements Runnable {
	public enum MessageType {
		Version, UDPTunnel, Authenticate, Ping, Reject, ServerSync, ChannelRemove, ChannelState, UserRemove, UserState, BanList, TextMessage, PermissionDenied, ACL, QueryUsers, CryptSetup, ContextActionAdd, ContextAction, UserList, VoiceTarget, PermissionQuery, CodecVersion, UserStats, RequestBlob, ServerConfig
	}

	public enum UDPMessageType {
		UDPVoiceCELTAlpha, UDPPing, UDPVoiceSpeex, UDPVoiceCELTBeta
	};

	public static final boolean ANDROID = true;;

	private static final int frameSize = 48000 / 100;
	private static final int protocolVersion = (1 << 16) | (2 << 8)
			| (3 & 0xFF);
	private AudioTrack at;
	private DataOutputStream out;
	private DataInputStream in;
	// private boolean authenticated = false;
	private int session;
	private Thread pingThread;
	private String host;
	private int port;
	private String username;
	private String password;

	private SWIGTYPE_p_CELTMode celtMode;
	private SWIGTYPE_p_CELTDecoder celtDecoder;

	public MumbleClient(final String host_, final int port_,
			final String username_, final String password_) {
		host = host_;
		port = port_;
		username = username_;
		password = password_;
	}

	public final void run() {
		try {
			final SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(null, new TrustManager[] { new LocalSSLTrustManager() },
					null);
			final SSLSocketFactory factory = ctx.getSocketFactory();
			final SSLSocket socket = (SSLSocket) factory.createSocket(host,
					port);
			socket.setUseClientMode(true);
			socket.setEnabledProtocols(new String[] { "TLSv1" });
			socket.startHandshake();

			handleProtocol(socket);
		} catch (final NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (final KeyManagementException e) {
			e.printStackTrace();
		} catch (final UnknownHostException e) {
			e.printStackTrace();
		} catch (final IOException e) {
			e.printStackTrace();
		}
	}

	public final void sendMessage(final MessageType t,
			final MessageLite.Builder b) throws IOException {
		final MessageLite m = b.build();
		final short type = (short) t.ordinal();
		final int length = m.getSerializedSize();

		out.writeShort(type);
		out.writeInt(length);
		out.write(m.toByteArray());

		if (t != MessageType.Ping) {
			if (ANDROID) {
				Log.i("mumbleclient", "<<< " + t);
			} else {
				System.out.println("<<< " + t);
			}
		}
	}

	private void handleProtocol(final Socket socket) throws IOException {
		out = new DataOutputStream(socket.getOutputStream());
		in = new DataInputStream(socket.getInputStream());

		final Version.Builder v = Version.newBuilder();
		v.setVersion(protocolVersion);
		v.setRelease("javalib 0.0.1-dev");

		final Authenticate.Builder a = Authenticate.newBuilder();
		a.setUsername(username);
		a.setPassword(password);
		a.addCeltVersions(0x8000000b);

		sendMessage(MessageType.Version, v);
		sendMessage(MessageType.Authenticate, a);

		while (socket.isConnected()) {
			final short type = in.readShort();
			final int length = in.readInt();
			final byte[] msg = new byte[length];
			in.readFully(msg);
			processMsg(MessageType.class.getEnumConstants()[type], msg);
		}
	}

	private void processMsg(final MessageType t, final byte[] buffer)
			throws IOException {
		switch (t) {
		case Ping:
			// ignore
			break;
		case ServerSync:
			final ServerSync ss = ServerSync.parseFrom(buffer);
			session = ss.getSession();
			// authenticated = true;

			pingThread = new Thread(new PingThread(this), "ping");
			pingThread.start();
			if (ANDROID) {
				Log.i("mumbleclient", ">>> " + t);
			} else {
				System.out.println(">>> " + t);
			}

			final UserState.Builder us = UserState.newBuilder();
			us.setSession(session);
			us.setPluginContext(ByteString
					.copyFromUtf8("Manual placement\000test"));
			sendMessage(MessageType.UserState, us);

			if (ANDROID) {
				at = new AudioTrack(
						AudioManager.STREAM_VOICE_CALL,
						48000, // set this to channel rate
						AudioFormat.CHANNEL_CONFIGURATION_MONO,
						AudioFormat.ENCODING_PCM_16BIT, 32768,
						AudioTrack.MODE_STREAM);
				at.play();
			}

			int[] error = new int[1];
			celtMode = celt.celt_mode_create(48000, 48000 / 100, error);
			celtDecoder = celt.celt_decoder_create(celtMode, 1, error);

			break;
		case UDPTunnel:
			processVoicePacket(buffer);
			break;
		default:
			if (ANDROID) {
				Log.i("mumbleclient", "unhandled message type " + t);
			} else {
				System.out.println("unhandled message type " + t);
			}
		}
	}

	private void processVoicePacket(final byte[] buffer) {
		final UDPMessageType type = UDPMessageType.values()[buffer[0] >> 5 & 0x7];
		// int flags = buffer[0] & 0x1f;
		final byte[] pdsBuffer = new byte[buffer.length - 1];
		System.arraycopy(buffer, 1, pdsBuffer, 0, pdsBuffer.length);

		final PacketDataStream pds = new PacketDataStream(pdsBuffer);
		final long uiSession = pds.readLong();
		final long iSeq = pds.readLong();

//		if (ANDROID)
//			Log.i("mumbleclient", "Type: " + type + " uiSession: " + uiSession
//					+ " iSeq: " + iSeq);
//		else
//			System.out.println("Type: " + type + " uiSession: " + uiSession
//					+ " iSeq: " + iSeq);

		int header = 0;
		int frames = 0;
		final ArrayList<short[]> frameList = new ArrayList<short[]>();
		do {
			header = pds.next();
			if (header > 0) {
				frameList.add(pds.dataBlock(header & 0x7f));
			} else {
				pds.skip(header & 0x7f);
			}

			++frames;
		} while (((header & 0x80) > 0) && pds.isValid());

//		if (ANDROID)
//			Log.i("mumbleclient", "frames: " + frames + " valid: "
//					+ pds.isValid());
//		else
//			System.out
//					.println("frames: " + frames + " valid: " + pds.isValid());

		if (pds.left() > 0) {
			final float x = pds.readFloat();
			final float y = pds.readFloat();
			final float z = pds.readFloat();
			if (ANDROID) {
				Log.i("mumbleclient", "x: " + x + " y: " + y + " z: " + z);
			} else {
				System.out.println("x: " + x + " y: " + y + " z: " + z);
			}
		}

		short[] audioOut = new short[frameSize];
		for (short[] frame : frameList) {
			celt.celt_decode(celtDecoder, frame, frame.length, audioOut);
			if (ANDROID) {
				at.write(audioOut, 0, frameSize);
			}
		}
	}

	@Override
	protected final void finalize() {
		celt.celt_decoder_destroy(celtDecoder);
		celt.celt_mode_destroy(celtMode);
	}
}