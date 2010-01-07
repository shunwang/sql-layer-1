package com.akiba.cserver;

import java.nio.ByteBuffer;

import com.akiba.message.Message;

public class WriteRowResponse extends Message {

    public static short TYPE;

	private int resultCode;
	
	public int getResultCode() {
		return resultCode;
	}

	public void setResultCode(int resultCode) {
		this.resultCode = resultCode;
	}

	public WriteRowResponse() {
		super(TYPE);
	}
	
	public WriteRowResponse(final int resultCode) {
		super(TYPE);
		setResultCode(resultCode);
	}

	@Override
	public void read(ByteBuffer payload) throws Exception
    {
		super.read(payload);
		resultCode = payload.getShort();
	}

	@Override
	public void write(ByteBuffer payload) throws Exception
    {
		payload.putShort((short)resultCode);
	}
}
