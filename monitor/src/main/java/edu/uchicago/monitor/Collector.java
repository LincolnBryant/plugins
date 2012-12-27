package edu.uchicago.monitor;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.netty.bootstrap.ConnectionlessBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;

import static org.jboss.netty.buffer.ChannelBuffers.*;

import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.DatagramChannel;
import org.jboss.netty.channel.socket.DatagramChannelFactory;
import org.jboss.netty.channel.socket.nio.NioDatagramChannelFactory;
import org.jboss.netty.handler.codec.string.StringDecoder;
import org.jboss.netty.handler.codec.string.StringEncoder;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Collector {

	final Logger logger = LoggerFactory.getLogger(Collector.class);

	private String servername;
	private Properties properties;

	private int tos;
	private int pid;
	private byte fseq;
	private int dictid;

	private DatagramChannelFactory f;
	private ConnectionlessBootstrap b;

	public final Map<Integer, FileStatistics> fmap = new ConcurrentHashMap<Integer, FileStatistics>();

	private AtomicInteger connectionAttempts = new AtomicInteger();
	private AtomicInteger currentConnections = new AtomicInteger();
	private AtomicInteger successfulConnections = new AtomicInteger();
	public AtomicLong totBytesRead = new AtomicLong();
	public AtomicLong totBytesWriten = new AtomicLong();
	private CollectorAddresses ca = new CollectorAddresses();
	
	// Timer timer = new Timer();

	Collector(Properties properties) {
		this.properties = properties;
		init();
	}

	private void init() {

		String pServerName = properties.getProperty("servername"); // if not
																	// defined
																	// will try
																	// to get it
																	// using
																	// getHostName.
		if (pServerName != null) {
			servername = pServerName;
		} else {
			try {
				servername = java.net.InetAddress.getLocalHost().getHostName();
				logger.info("server name: " + servername);
			} catch (UnknownHostException e) {
				logger.error("Could not get server's hostname. Will set it to xxx.abc.def");
				servername = "xxx.abc.def";
				e.printStackTrace();
			}
		}

		ca.init(properties);
		logger.info(ca.toString());

		tos = (int) (System.currentTimeMillis() / 1000L);

		try {
			pid = Integer.parseInt(new File("/proc/self").getCanonicalFile().getName());
		} catch (Exception e) {
			logger.warn("could not get PID from /proc/self. Setting it to 123456.");
			pid = 123456;
		}

		f = new NioDatagramChannelFactory(Executors.newCachedThreadPool());
		b = new ConnectionlessBootstrap(f);

		b.setPipelineFactory(new ChannelPipelineFactory() {
			public ChannelPipeline getPipeline() throws Exception {
				return Channels.pipeline(new StringEncoder(CharsetUtil.ISO_8859_1), new StringDecoder(CharsetUtil.ISO_8859_1),
						new SimpleChannelUpstreamHandler());
			}
		});
		// b.setOption("receiveBufferSizePredictorFactory", new
		// FixedReceiveBufferSizePredictorFactory(1024));
		// b.setOption("sendBufferSize",32000);
		b.setOption("broadcast", "true");
		b.setOption("connectTimeoutMillis", 10000);

		for (Address a : ca.summary) {
			Timer timer = new Timer();
			timer.schedule(new SendSummaryStatisticsTask(a), 0, a.delay * 1000);
		}
		for (Address a : ca.detailed) {
			Timer timer = new Timer();
			timer.schedule(new SendDetailedStatisticsTask(a), 0, a.delay * 1000);
		}
	}

	public void addConnectionAttempt() {
		connectionAttempts.getAndIncrement();
	}

	public void openEvent(int connectionId, FileStatistics fs) {
		// Note that status may be null - only available if client requested it
		logger.info(">>>Opened " + connectionId + "\n" + fs.toString());
		fs.state |= 0x0011; // set first and second bit
	}

	public void closeEvent(int connectionId, int fh) {
		logger.info(">>>Closed " + connectionId + "\n" + fmap.get(fh).toString());
		// if detailed monitoring is ON collector will remove it from map
		if (ca.reportDetailed == false)
			fmap.remove(fh);
		else
			fmap.get(fh).state |= 0x0004; // set third bit
	}

	public void connectedEvent(int connectionId, SocketAddress remoteAddress) {
		currentConnections.getAndIncrement();
		logger.info(">>>Connected " + connectionId + " " + remoteAddress);
	}

	public void disconnectedEvent(int connectionId, long duration) {
		currentConnections.getAndDecrement();
		successfulConnections.getAndIncrement();
		logger.info(">>>Disconnected " + connectionId + " " + duration + "ms");
	}

	@Override
	public String toString() {
		String res = new String();
		res += "SUMMARY ----------------------------------\n";
		res += "Connection Attempts:     " + connectionAttempts.get() + "\n";
		res += "Current Connections:     " + currentConnections.toString() + "\n";
		res += "Connections established: " + successfulConnections.toString() + "\n";
		res += "Bytes Read:              " + totBytesRead.toString() + "\n";
		res += "Bytes Written:           " + totBytesWriten.toString() + "\n";
		res += "SUMMARY ----------------------------------\n";
		return res;
	}

	public void SendMapMessage(Integer dictid, String content) {
		logger.info("sending map message: " + content);

		for (Address a : ca.detailed) {
			new MapMessagesSender(a, dictid, content);
		}
	}

	private class MapMessagesSender extends Thread {
		private InetSocketAddress destination;
		private Integer dictid;
		private String content;

		MapMessagesSender(Address a, Integer dictid, String content) {
			destination = new InetSocketAddress(a.address, a.port);
			this.dictid = dictid;
			this.content = content;
			this.run();
		}

		public void run() {
			try {
				DatagramChannel c = (DatagramChannel) b.bind(new InetSocketAddress(0));
				String authinfo = "\n&p=SSL&n=ivukotic&h=hostname&o=UofC&r=Production&g=higgs&m=fuck";
				content += authinfo;
				short plen = (short) (12 + content.length());
				ChannelBuffer db = dynamicBuffer(plen);

				// main header
				db.writeByte((byte) 117); // 'u'
				db.writeByte((byte) fseq);
				db.writeShort(plen);
				db.writeInt(tos);
				db.writeInt(dictid); // this is dictID
				db.writeBytes(content.getBytes());
				ChannelFuture f = c.write(db, destination);

				f.addListener(new ChannelFutureListener() {
					public void operationComplete(ChannelFuture future) throws Exception {
						if (future.isSuccess()) {
							logger.debug("OK sent. ");
						} else {
							logger.error("NOT sent. ");
						}
					}
				});
				c.close();
			} catch (Exception e) {
				logger.error("unrecognized exception: " + e.getMessage());
			}
		}

	}

	private class SendSummaryStatisticsTask extends TimerTask {
		private String info;
		private Address a;

		SendSummaryStatisticsTask(Address a) {
			this.a = a;
			info = "<stats id=\"info\"><host>" + servername + "</host><port>" + a.port + "</port><name>anon</name></stats>";
		}

		public void run() {

			logger.info("sending summary stream");

			long tod = System.currentTimeMillis() / 1000L - 60;
			String sgen = "<stats id=\"sgen\"><as>1</as><et>60000</et><toe>" + (tod + 60) + "</toe></stats>";
			String link = "<stats id=\"link\"><num>1</num><maxn>1</maxn><tot>20</tot><in>" + totBytesWriten.toString() + "</in><out>" + totBytesRead.toString()
					+ "</out><ctime>0</ctime><tmo>0</tmo><stall>0</stall><sfps>0</sfps></stats>";
			String xmlmessage = "<statistics tod=\"" + tod + "\" ver=\"v1.9.12.21\" tos=\"" + tos + "\" src=\"" + servername
					+ "\" pgm=\"xrootd\" ins=\"anon\" pid=\"" + pid + "\">" + info + sgen + link + "</statistics>";
			// logger.info(xmlmessage);

			DatagramChannel c = (DatagramChannel) b.bind(new InetSocketAddress(0));

			ChannelFuture f = c.write(xmlmessage, new InetSocketAddress(a.address, a.port));

			f.awaitUninterruptibly();
			if (!f.isSuccess()) {
				f.getCause().printStackTrace();
			}
			// else { logger.info("sent ok"); }
			c.close();
		}
	}

	private class SendDetailedStatisticsTask extends TimerTask {
		private InetSocketAddress destination;

		// private String info;
		SendDetailedStatisticsTask(Address a) {
			destination = new InetSocketAddress(a.address, a.port);
		}

		public void run() {
			sendFstream();
		}

		private void sendFstream() {
			logger.info("sending detailed stream");
			DatagramChannel c = (DatagramChannel) b.bind(new InetSocketAddress(0));
			short plen = (short) (24 + 32 * fmap.size()); // this is length of 2
															// mandatory headers
			ChannelBuffer db = dynamicBuffer(plen);

			// main header
			db.writeByte((byte) 102); // 'f'
			db.writeByte((byte) fseq);
			db.writeShort(plen); // will be replaced later
			db.writeInt(tos);

			// first timing header
			db.writeByte((byte) 2); // 2 - means isTime
			db.writeByte((byte) 0); // no meaning here
			db.writeShort(16); // size of this header
			db.writeShort(0); // since this is TOD - this is nRec[0]
			db.writeShort(0); // this gives total number of "subpackages". will
								// be overwritten bellow
			db.writeInt(tos); // unix time - this should be start of package
								// collection time
			db.writeInt(tos); // unix time - this should be time of sending.

			int subpackets = 0;
			Iterator<Entry<Integer, FileStatistics>> it = fmap.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Integer, FileStatistics> ent = (Map.Entry<Integer, FileStatistics>) it.next();
				FileStatistics fs = (FileStatistics) ent.getValue();
				Integer dictID = ent.getKey();

				if (dictID < 0)
					continue; // file has been requested but not yet really
								// opened.

				if ((fs.state & 0x0001) > 0) { // file OPEN structure
					// header
					db.writeByte((byte) 1); // 1 - means isOpen
					db.writeByte((byte) 0x01); // the lfn is present - 0x02 is
												// R/W
					int len = 21 + fs.filename.length();
					plen += len;
					db.writeShort(len); // size
					db.writeInt(fs.fileId); // replace with dictid of the file

					db.writeLong(fs.filesize); // filesize at open.
					if (true) { // check if Filenames should be reported.
						db.writeInt(dictID);// user_dictid
						logger.error("userID: "+dictID);
						db.writeBytes(fs.filename.getBytes());// maybe should be
																// forced to
																// "US-ASCII"?
						db.writeByte(0x0); // to make this "C" string. end with
											// null character.
					}

					// reset the first bit
					fs.state &= 0xFFFE;
					subpackets += 1;
				}

				db.writeByte((byte) 3); // fileIO report
				db.writeByte((byte) 0); // no meaning
				db.writeShort(32); // 3*longlong + this header itself
				db.writeInt(fs.fileId); // replace with dictid of the file
				db.writeLong(fs.bytesRead.get());
				db.writeLong(fs.bytesVectorRead.get());
				db.writeLong(fs.bytesWritten.get());
				subpackets += 1;

				if ((fs.state & 0x0004) > 0) { // add fileclose structure
					// header
					db.writeByte((byte) 0); // 0 - means isClose
					db.writeByte((byte) 0x00); // 0- basic 2- MonStatXFR +
												// MonStatOPS 1-
												// close due to disconnect 4-
												// XFR + OPS + MonStatSDV
					db.writeShort(8 + 24); // size of this header
					plen += 32;
					db.writeInt(fs.fileId); // replace with dictid of the file

					//
					db.writeLong(fs.bytesRead.get());
					db.writeLong(fs.bytesVectorRead.get());
					db.writeLong(fs.bytesWritten.get());

					// if (false){
					// here if ops or SDV was required
					// }

					// remove it
					it.remove();
					subpackets += 1;
				}

			}

			db.setShort(2, plen);
			db.setShort(14, subpackets);

			ChannelFuture f = c.write(db, destination);
			f.awaitUninterruptibly();
			if (!f.isSuccess()) {
				f.getCause().printStackTrace();
			}
			// else { logger.info("sent ok");}
			c.close();
		}

	}

}
