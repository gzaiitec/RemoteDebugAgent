package com.aiitec.debugAgent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StreamUtils {

	public static long synCopy(InputStream input, OutputStream output)
            throws IOException {
        byte[] buffer = new byte[32768];
        long count = 0;
        int n = 0;
        long lastTime = System.currentTimeMillis();
        while (-1 != (n = input.read(buffer))) {
        	if(System.currentTimeMillis()-lastTime>1000){
          	  lastTime = System.currentTimeMillis();
//          	  log.debug((System.currentTimeMillis()-startTime)/1000+"√Î,TcpBridge£∫∂¡»°¥Û–°"+count);
            }
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }
	
	public static Thread asynCopy(final InputStream is,final OutputStream os,final OnCloseCallback onCloseCallback){
		Thread t = new Thread(){
			public void run(){
				try {
					StreamUtils.synCopy(is, os);
				} catch (IOException e) {
					onCloseCallback.onException(e);
				}finally{
					onCloseCallback.onClose();
				}
			}
		};
		t.start();
		return t; 
	}
}
