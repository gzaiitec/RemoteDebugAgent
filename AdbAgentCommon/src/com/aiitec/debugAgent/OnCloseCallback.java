package com.aiitec.debugAgent;

public interface OnCloseCallback {
	public void onClose();
	
	public void onException(Throwable e);
}
