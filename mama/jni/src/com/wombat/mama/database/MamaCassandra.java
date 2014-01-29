package com.wombat.mama;

import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Host;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.BoundStatement;


import com.wombat.mama.*;

public class MamaCassandra implements MamaDatabase 
{
    private String            nodes;
    private Cluster           cluster;
    private Session           session;
    private String            insertStmtStr="INSERT INTO openmama_keyspace.messages (id, fieldmap) VALUES (?, ?);";
    private PreparedStatement insertStmt;
    
    
    private void MamaCassandra()
    {
        nodes=Mama.getProperty("mama.cassandra.nodes");
    }
    public void connect()
    {
        Cluster cluster = Cluster.builder()
                            .addContactPoints(nodes)
                            .build();

        session  = cluster.connect();
        
        session.execute("CREATE KEYSPACE IF NOT EXISTS openmama_keyspace WITH replication " + 
                        "= {'class':'SimpleStrategy', 'replication_factor':3};");
        session.execute("create table IF NOT EXISTS openmama_keyspace.messages (id text, fieldmap map <int,text>, PRIMARY KEY (id))");
        this.insertStmt = session.prepare(insertStmtStr);
    }
    public void writeMsg(MamaMsg msg, MamaDictionary dictionary, MamaSubscription subscription)
    {
        BoundStatement bound = new BoundStatement(insertStmt);
        Map<Integer, String> fieldMap = new HashMap<Integer, String>();
        try{
            for (Iterator iterator = msg.iterator(dictionary); iterator.hasNext();) {
                MamaMsgField field = (MamaMsgField) iterator.next();
                fieldMap.put(field.getFid(), msg.getFieldAsString(field.getFid(),dictionary));
            }
            session.executeAsync(bound.bind(subscription.getSymbol() + "-" + msg.getSeqNum(),  fieldMap));
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
    public void clear()
    {
        session.execute("DROP KEYSPACE IF EXISTS openmama_keyspace;");
        session.execute("CREATE KEYSPACE openmama_keyspace WITH replication " + 
                        "= {'class':'SimpleStrategy', 'replication_factor':3};");
        session.execute("create table openmama_keyspace.messages (id text, fieldmap map <int,text>, PRIMARY KEY (id))");
        this.insertStmt = session.prepare(insertStmtStr);
    }
    public void close()
    {
        
    }
}
