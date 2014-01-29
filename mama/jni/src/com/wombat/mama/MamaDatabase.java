package com.wombat.mama;

public interface MamaDatabase{
    public void connect();
    public void writeMsg(MamaMsg msg, MamaDictionary dictionary, MamaSubscription subscription);
    public void clear();
    public void close();
}
