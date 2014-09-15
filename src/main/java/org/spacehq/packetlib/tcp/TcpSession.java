package org.spacehq.packetlib.tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.TimeoutException;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.spacehq.packetlib.Session;
import org.spacehq.packetlib.TimeoutHandler;
import org.spacehq.packetlib.TimeoutType;
import org.spacehq.packetlib.event.session.*;
import org.spacehq.packetlib.packet.Packet;
import org.spacehq.packetlib.packet.PacketProtocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TcpSession extends SimpleChannelInboundHandler<Packet> implements Session {

	private String host;
	private int port;
	private PacketProtocol protocol;
	private Bootstrap bootstrap;
	private EventLoopGroup group;
	private Channel channel;
	private boolean disconnected = false;
	private boolean writing = false;
	private int compressionThreshold = -1;
	private int readTimeout = 30;
	private int writeTimeout = 0;
	private TimeoutHandler timeoutHandler = null;
	private TimeoutHandler connectTimeoutHandler = null;
	private List<Packet> packets = new ArrayList<Packet>();

	private Map<String, Object> flags = new HashMap<String, Object>();
	private List<SessionListener> listeners = new ArrayList<SessionListener>();

	public TcpSession(String host, int port, PacketProtocol protocol, EventLoopGroup group, Bootstrap bootstrap, TimeoutHandler connectTimeoutHandler) {
		this.host = host;
		this.port = port;
		this.protocol = protocol;
		this.group = group;
		this.bootstrap = bootstrap;
		this.connectTimeoutHandler = connectTimeoutHandler;
	}

	@Override
	public void connect() {
		this.connect(true);
	}

	@Override
	public void connect(boolean wait) {
		if(this.bootstrap == null) {
			if(!this.disconnected) {
				return;
			} else {
				throw new IllegalStateException("Session has already been disconnected.");
			}
		}

		ChannelFuture future = this.bootstrap.connect();
		future.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture channelFuture) throws Exception {
				if(channelFuture.cause() instanceof ConnectTimeoutException && connectTimeoutHandler != null) {
					connectTimeoutHandler.onTimeout(TcpSession.this, TimeoutType.CONNECT);
				}
			}
		});

		this.bootstrap = null;
		if(wait) {
			future.syncUninterruptibly();
			while(this.channel == null && !this.disconnected) {
				try {
					Thread.sleep(5);
				} catch(InterruptedException e) {
				}
			}
		}
	}

	@Override
	public String getHost() {
		return this.host;
	}

	@Override
	public int getPort() {
		return this.port;
	}

	@Override
	public PacketProtocol getPacketProtocol() {
		return this.protocol;
	}

	@Override
	public Map<String, Object> getFlags() {
		return new HashMap<String, Object>(this.flags);
	}

	@Override
	public boolean hasFlag(String key) {
		return this.getFlags().containsKey(key);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getFlag(String key) {
		Object value = this.getFlags().get(key);
		if(value == null) {
			return null;
		}

		try {
			return (T) value;
		} catch(ClassCastException e) {
			throw new IllegalStateException("Tried to get flag \"" + key + "\" as the wrong type. Actual type: " + value.getClass().getName());
		}
	}

	@Override
	public void setFlag(String key, Object value) {
		this.flags.put(key, value);
	}

	@Override
	public List<SessionListener> getListeners() {
		return new ArrayList<SessionListener>(this.listeners);
	}

	@Override
	public void addListener(SessionListener listener) {
		this.listeners.add(listener);
	}

	@Override
	public void removeListener(SessionListener listener) {
		this.listeners.remove(listener);
	}

	@Override
	public void callEvent(SessionEvent event) {
		for(SessionListener listener : this.listeners) {
			try {
				event.call(listener);
			} catch(Throwable t) {
				System.err.println("[WARNING] Throwable caught while firing event.");
				t.printStackTrace();
			}
		}
	}

	@Override
	public int getCompressionThreshold() {
		return this.compressionThreshold;
	}

	@Override
	public void setCompressionThreshold(int threshold) {
		this.compressionThreshold = threshold;
		if(this.channel != null) {
			if(this.compressionThreshold >= 0) {
				if(this.channel.pipeline().get("compression") == null) {
					this.channel.pipeline().addBefore("codec", "compression", new TcpPacketCompression(this));
				}
			} else if(this.channel.pipeline().get("compression") != null) {
				this.channel.pipeline().remove("compression");
			}
		}
	}

	@Override
	public int getReadTimeout() {
		return this.readTimeout;
	}

	@Override
	public void setReadTimeout(int timeout) {
		this.readTimeout = timeout;
		this.refreshReadTimeoutHandler();
	}

	@Override
	public int getWriteTimeout() {
		return this.readTimeout;
	}

	@Override
	public void setWriteTimeout(int timeout) {
		this.writeTimeout = timeout;
		this.refreshWriteTimeoutHandler();
	}

	@Override
	public TimeoutHandler getTimeoutHandler() {
		return this.timeoutHandler;
	}

	@Override
	public void setTimeoutHandler(TimeoutHandler timeoutHandler) {
		this.timeoutHandler = timeoutHandler;
	}

	@Override
	public boolean isConnected() {
		return this.channel != null && this.channel.isOpen() && !this.disconnected;
	}

	@Override
	public void send(final Packet packet) {
		this.writing = true;
		if(this.channel == null) {
			this.writing = false;
			return;
		}

		this.channel.writeAndFlush(packet).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) throws Exception {
				writing = false;
				if(!future.isSuccess()) {
					exceptionCaught(null, future.cause());
				} else {
					callEvent(new PacketSentEvent(TcpSession.this, packet));
				}
			}
		});

		if(packet.isPriority()) {
			while(this.writing) {
				try {
					Thread.sleep(2);
				} catch(InterruptedException e) {
				}
			}
		}
	}

	@Override
	public void disconnect(String reason) {
		if(this.disconnected) {
			return;
		}

		this.disconnected = true;
		if(this.writing) {
			while(this.writing) {
				try {
					Thread.sleep(2);
				} catch(InterruptedException e) {
				}
			}

			try {
				Thread.sleep(250);
			} catch(InterruptedException e) {
			}
		}

		if(reason == null) {
			reason = "Connection closed.";
		}

		if(this.channel != null) {
			if(this.channel.isOpen()) {
				this.callEvent(new DisconnectingEvent(this, reason));
			}

			try {
				this.channel.close().syncUninterruptibly();
			} catch(Throwable t) {
			}
		}

		this.callEvent(new DisconnectedEvent(this, reason));
		if(this.group != null) {
			try {
				this.group.shutdownGracefully();
			} catch(Throwable t) {
			}
		}

		this.channel = null;
	}

	protected void refreshReadTimeoutHandler() {
		this.refreshReadTimeoutHandler(this.channel);
	}

	protected void refreshReadTimeoutHandler(Channel channel) {
		if(channel != null) {
			if(this.readTimeout <= 0) {
				if(channel.pipeline().get("readTimeout") != null) {
					channel.pipeline().remove("readTimeout");
				}
			} else {
				if(channel.pipeline().get("readTimeout") == null) {
					channel.pipeline().addFirst("readTimeout", new ReadTimeoutHandler(this.readTimeout));
				} else {
					channel.pipeline().replace("readTimeout", "readTimeout", new ReadTimeoutHandler(this.readTimeout));
				}
			}
		}
	}

	protected void refreshWriteTimeoutHandler() {
		this.refreshWriteTimeoutHandler(this.channel);
	}

	protected void refreshWriteTimeoutHandler(Channel channel) {
		if(channel != null) {
			if(this.writeTimeout <= 0) {
				if(channel.pipeline().get("writeTimeout") != null) {
					channel.pipeline().remove("writeTimeout");
				}
			} else {
				if(channel.pipeline().get("writeTimeout") == null) {
					channel.pipeline().addFirst("writeTimeout", new WriteTimeoutHandler(this.writeTimeout));
				} else {
					channel.pipeline().replace("writeTimeout", "writeTimeout", new WriteTimeoutHandler(this.writeTimeout));
				}
			}
		}
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		if(this.disconnected) {
			ctx.channel().close().syncUninterruptibly();
			return;
		}

		this.channel = ctx.channel();
		this.disconnected = false;
		this.callEvent(new ConnectedEvent(this));
		new PacketHandleThread().start();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		this.disconnect("Connection closed.");
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		this.writing = false;
		if(!this.disconnected) {
			if(cause instanceof TimeoutException) {
				if(this.timeoutHandler != null) {
					this.timeoutHandler.onTimeout(this, cause instanceof ReadTimeoutException ? TimeoutType.READ : TimeoutType.WRITE);
				}

				this.disconnect((cause instanceof ReadTimeoutException ? "Read" : "Write") + " timed out.");
			} else if(cause instanceof ConnectTimeoutException) {
				if(this.connectTimeoutHandler != null) {
					this.connectTimeoutHandler.onTimeout(this, TimeoutType.CONNECT);
				}

				this.disconnect("Connection timed out.");
			} else {
				this.disconnect("Internal network exception: " + cause.toString());
				cause.printStackTrace();
			}
		}

		this.disconnected = true;
	}

	@Override
	protected void messageReceived(ChannelHandlerContext ctx, Packet packet) throws Exception {
		if(!packet.isPriority()) {
			this.packets.add(packet);
		}
	}

	private class PacketHandleThread extends Thread {
		@Override
		public void run() {
			try {
				while(!disconnected) {
					while(packets.size() > 0) {
						callEvent(new PacketReceivedEvent(TcpSession.this, packets.remove(0)));
					}

					try {
						Thread.sleep(5);
					} catch(InterruptedException e) {
					}
				}
			} catch(Throwable t) {
				try {
					exceptionCaught(null, t);
				} catch(Exception e) {
					System.err.println("Exception while handling exception!");
					e.printStackTrace();
				}
			}
		}
	}

}
