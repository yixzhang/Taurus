package com.dp.bigdata.taurus.zookeeper.common.infochannel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.data.Stat;

import com.dp.bigdata.taurus.zookeeper.common.MachineType;
import com.dp.bigdata.taurus.zookeeper.common.TaurusZKException;
import com.dp.bigdata.taurus.zookeeper.common.infochannel.guice.ZooKeeperProvider;
import com.dp.bigdata.taurus.zookeeper.common.infochannel.interfaces.ClusterInfoChannel;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;

abstract class TaurusZKInfoChannel implements ClusterInfoChannel{
	private static final Log LOG = LogFactory.getLog(TaurusZKInfoChannel.class);

	private static final int MAX_HEARTBEAT_LENGTH = 100;

	protected static final String SEP = "/";
	protected static final String BASE = "taurus";
	private static final String HEARTBEATS = "heartbeats";
	private static final String REALTIME = "realtime";
	private static final String INFO = "info";

	protected ZooKeeper zk;
	protected MachineType mt;
	protected String ip;
	

	@Inject
	TaurusZKInfoChannel(ZooKeeper zk){
		this.zk = zk;
	}
	
	@Override
	public void reconnectToCluster(Watcher watcher) {
	    try{
	        zk.close();
	        Injector injector = Guice.createInjector(new ZooKeeperModule());
	        zk = injector.getInstance(ZooKeeper.class);
	        zk.register(watcher);
	        for(int i=0; i<10; i++){
	            if(zk.getState()!=States.CONNECTED){
	                Thread.sleep(1000);
	            }
	        }
	        mkPath(CreateMode.EPHEMERAL, BASE, HEARTBEATS, mt.getName(), REALTIME, ip);
        } catch(Exception e){
            throw new TaurusZKException(e);
        }
	}

	@Override
	public void connectToCluster(MachineType mt, String ip) {
	    this.mt = mt;
	    this.ip = ip;
		try{
			if(!existPath(BASE)){
				setupBasePath();
			}
			mkPath(CreateMode.EPHEMERAL, BASE, HEARTBEATS, mt.getName(), REALTIME, ip);
			mkPath(BASE, HEARTBEATS, mt.getName(), INFO, ip);
		} catch(Exception e){
			throw new TaurusZKException(e);
		}
	}

	@Override
	public boolean exists(MachineType mt, String ip) {
		try{
			return existPath(BASE, HEARTBEATS, mt.getName(), REALTIME, ip);
		} catch(Exception e){
			LOG.error(e.getMessage(),e);
			return false;
		}
	}

	@Override
	public void updateHeartbeatInfo(MachineType mt, String ip, Object info) {
		try{
			List<Object> heartbeatInfoList = new ArrayList<Object>(getHeartbeatInfo(mt, ip));
			if(heartbeatInfoList.size() >= MAX_HEARTBEAT_LENGTH){
				heartbeatInfoList.remove(heartbeatInfoList.size()-1);
			}
			heartbeatInfoList.add(info);

			setData(heartbeatInfoList, BASE, HEARTBEATS, mt.getName(), INFO, ip);
		} catch(Exception e){
			throw new TaurusZKException(e);
		}
	}

	@Override
	public List<Object> getHeartbeatInfo(MachineType mt, String ip) {
		try{
			return Collections.unmodifiableList(
					(List<Object>)getData(BASE, HEARTBEATS, mt.getName(), INFO, ip));
		} catch(Exception e){
			return Collections.unmodifiableList(new ArrayList<Object>());
		}	
	}

	@Override
	public Set<String> getAllConnectedMachineIps(MachineType mt, Watcher watcher) {
		try{
			List<String> ips = getChildrenNodeName(watcher, BASE, HEARTBEATS, mt.getName(), REALTIME);
			Set<String> newIps = new HashSet<String>();
			for(String ip: ips){
				newIps.add(ip);
			}
			return Collections.unmodifiableSet(newIps);
		} catch(Exception e){
			return Collections.unmodifiableSet(new HashSet<String>(0));
		}
	}

	@Override
	public void close(){
		try{
			zk.close();
		} catch(Exception e){
			throw new TaurusZKException(e);
		}
	}

	private void setupBasePath() 
	throws KeeperException, InterruptedException, IOException{
		mkPath(BASE);
		mkPath(BASE, HEARTBEATS);
		mkPath(BASE, HEARTBEATS, MachineType.SERVER.getName());
		mkPath(BASE, HEARTBEATS, MachineType.SERVER.getName(), REALTIME);
		mkPath(BASE, HEARTBEATS, MachineType.SERVER.getName(), INFO);
		mkPath(BASE, HEARTBEATS, MachineType.AGENT.getName());
		mkPath(BASE, HEARTBEATS, MachineType.AGENT.getName(), REALTIME);
		mkPath(BASE, HEARTBEATS, MachineType.AGENT.getName(), INFO);
	}

	protected List<String> getChildrenNodeName(Watcher watcher, String ... path) 
	throws KeeperException, InterruptedException{
		return zk.getChildren(getFullPath(path), watcher);
	}

	protected void addChildrenWatcher(Watcher watcher, String... path) 
	throws KeeperException, InterruptedException{
		zk.getChildren(getFullPath(path), watcher);
	}

	protected void addDataWatcher(Watcher watcher, String... path) 
	throws KeeperException, InterruptedException{
		zk.getData(getFullPath(path), watcher, null);
	}

	private String getFullPath(String... node){
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < node.length; i++){
			sb.append(SEP).append(node[i]);
		}
		return sb.toString();
	}

	protected void mkPath(CreateMode mode, String... node) 
	throws KeeperException, InterruptedException, IOException{
		mkPath(mode, null, node);
	}

	protected void mkPath(CreateMode mode, Object data, String... node)
	throws KeeperException, InterruptedException, IOException{
		try{
			zk.create(getFullPath(node), changeObjectToByteArray(data), Ids.OPEN_ACL_UNSAFE, mode);
		} catch(KeeperException e){
			if(e.code() != Code.NODEEXISTS){
				throw e;
			}
		}
	}

	protected void mkPath(String... node) 
	throws KeeperException, InterruptedException, IOException{
		mkPath(CreateMode.PERSISTENT, node);
	}

	protected boolean existPath(String... node) 
	throws KeeperException, InterruptedException{
		Stat s = zk.exists(getFullPath(node), null);
		return (s != null);
	}

	protected void rmPath(String... node) 
	throws InterruptedException, KeeperException{
		try{
			Stat stat = zk.exists(getFullPath(node), null);
			if(stat != null){
				zk.delete(getFullPath(node), stat.getVersion());
			}
		} catch(KeeperException e){
			if(e.code() != Code.NONODE){
				throw e;
			}
		}
	} 
	
	protected void setData(Object data, String... node) 
	throws KeeperException, InterruptedException, IOException{
		Stat stat = zk.exists(getFullPath(node), null);
		if(stat == null){
			throw KeeperException.create(Code.NONODE, getFullPath(node));	
		}
		zk.setData(getFullPath(node), changeObjectToByteArray(data), stat.getVersion());
	}

	protected Object getData(Watcher watcher, String... node) 
	throws KeeperException, InterruptedException, IOException, ClassNotFoundException{
		byte[] bytes = zk.getData(getFullPath(node), watcher, null);
		return changeByteArrayToObject(bytes);
	}

	protected Object getData(String... node) 
	throws KeeperException, InterruptedException, IOException, ClassNotFoundException{
		return getData(null, node);
	}

	private Object changeByteArrayToObject(byte[] byteArray) 
	throws IOException, ClassNotFoundException{
		if(byteArray.length == 0){
			return null;
		}
		ByteArrayInputStream bis = new ByteArrayInputStream(byteArray);
		ObjectInput in = new ObjectInputStream(bis);
		Object o = in.readObject(); 
		bis.close();
		in.close();
		return o;
	}

	private byte[] changeObjectToByteArray(Object o) throws IOException{
		if(o == null){
			return new byte[0];
		}
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ObjectOutput out = new ObjectOutputStream(bos);   
		out.writeObject(o);
		byte[] byteArray = bos.toByteArray();
		out.close();
		bos.close();
		return byteArray;
	}
	private class ZooKeeperModule  extends AbstractModule{

        /* (non-Javadoc)
         * @see com.google.inject.AbstractModule#configure()
         */
        @Override
        protected void configure() {
            bind(ZooKeeper.class).toProvider(ZooKeeperProvider.class);
        }
	    
	}
    /* (non-Javadoc)
     * @see com.dp.bigdata.taurus.zookeeper.common.infochannel.interfaces.ClusterInfoChannel#registerWatcher(org.apache.zookeeper.Watcher)
     */
    @Override
    public void registerWatcher(Watcher w) {
        zk.register(w);   
    }
}
