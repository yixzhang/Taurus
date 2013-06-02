package com.dp.bigdata.taurus.agent.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.dp.bigdata.taurus.zookeeper.common.utils.ClassLoaderUtils;


public final class AgentEnvValue {
	
	private static final Log LOG = LogFactory.getLog(AgentEnvValue.class);

	public static final String CONF = "agentConf.properties";
	public static final String KEY_CHECK_INTERVALS = "checkIntervals";
	public static final String AGENT_ROOT_PATH = "taurusAgentPath";
	public static final String JOB_PATH = "taurusJobPath";
	public static final String LOG_PATH = "taurusLogPath";
	public static final String NEED_HADOOP_AUTHORITY = "needHadoopAuthority";
	public static final String NEED_SUDO_AUTHORITY = "needSudoAuthority";
	public static final String HOME_PATH = "homePath";
	
	public static final String HDFS_CONF = "hdfs.properties";
	public static final String HDFS_HOST = "hdfsHost";
	public static final String NAMENODE_PRINCIPAL = "namenodePrincipal";
	public static final String KERBEROS_PRINCIPAL = "clinetPrincipal";
	public static final String KEYTAB_FILE = "keytabFile";
	
	public static String getValue(String key){
		return getValue(key,"");
	}
	
	public static String getValue(String key,String defaultValue){
        try{
            Properties props = new Properties();
            InputStream in = ClassLoaderUtils.getDefaultClassLoader().getResourceAsStream(CONF);
            props.load(in);
            String result = props.getProperty(key);
            in.close();
            if(result == null) {
                return defaultValue;
            }
            return result;
        } catch(IOException e) {
            LOG.error(e.getMessage(),e);
            return defaultValue;
        }
    }
	
	public static String getHdfsValue(String key){
	    return getHdfsValue(key,"");
	}
	
	public static String getHdfsValue(String key,String defaultValue){
        try{
            Properties props = new Properties();
            InputStream in = ClassLoaderUtils.getDefaultClassLoader().getResourceAsStream(HDFS_CONF);
            props.load(in);
            String result = props.getProperty(key);
            in.close();
            if(result == null) {
                return defaultValue;
            }
            return result;
        } catch(IOException e) {
            LOG.error(e.getMessage(),e);
            return defaultValue;
        }
    }
	
}
