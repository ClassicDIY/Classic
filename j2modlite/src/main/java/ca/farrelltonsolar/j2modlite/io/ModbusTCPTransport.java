//License
/***
 * Java Modbus Library (jamod)
 * Copyright (c) 2002-2004, jamod development team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the author nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER AND CONTRIBUTORS ``AS
 * IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 ***/
package ca.farrelltonsolar.j2modlite.io;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import ca.farrelltonsolar.j2modlite.Modbus;
import ca.farrelltonsolar.j2modlite.ModbusIOException;
import ca.farrelltonsolar.j2modlite.msg.ModbusMessage;
import ca.farrelltonsolar.j2modlite.msg.ModbusResponse;
import ca.farrelltonsolar.j2modlite.util.ModbusUtil;

/**
 * Class that implements the Modbus transport flavor.
 *
 * @author Dieter Wimberger
 * @version 110410-jfh Cleaned up unused variables. Stopped spewing out error
 *          messages.
 */
public class ModbusTCPTransport implements ModbusTransport {

    // instance attributes
    private DataInputStream m_Input; // input stream
    private DataOutputStream m_Output; // output stream
    private int m_Timeout = Modbus.DEFAULT_TIMEOUT;
    private Socket m_Socket = null;
    private boolean headless = false; // Some TCP implementations are.
    private static final BytesInputStream m_ByteIn = new BytesInputStream(Modbus.MAX_MESSAGE_LENGTH);
    private static final BytesOutputStream m_ByteOut = new BytesOutputStream(Modbus.MAX_MESSAGE_LENGTH);
    /**
     * Sets the <tt>Socket</tt> used for message transport and prepares the
     * streams used for the actual I/O.
     *
     * @param socket the <tt>Socket</tt> used for message transport.
     * @throws IOException if an I/O related error occurs.
     */
    public void setSocket(Socket socket) throws IOException {
        if (m_Socket != null) {
            m_Socket.close();
            m_Socket = null;
        }
        m_Socket = socket;
        setTimeout(m_Timeout);

        prepareStreams(socket);
    }// setSocket

    public void setHeadless() {
        headless = true;
    }

    public void setTimeout(int time) {
        m_Timeout = time;

        if (m_Socket != null) {
            try {
                m_Socket.setSoTimeout(time);
            } catch (SocketException e) {
                // Not sure what to do.
            }
        }
    }

    public void close() throws IOException {
        m_Input.close();
        m_Output.close();
        m_Socket.close();
    }// close

    public void writeMessage(ModbusMessage msg) throws ModbusIOException {


        try {
            byte message[] = msg.getMessage();
            int transactionID = -1;
            int protocolID = -1;
            int unitID;
            int fupnctionCode;
            m_ByteOut.reset();
            if (!headless) {
                transactionID = msg.getTransactionID();
                m_ByteOut.writeShort(transactionID);
                protocolID = msg.getProtocolID();
                m_ByteOut.writeShort(protocolID);
                m_ByteOut
                        .writeShort((message != null ? message.length : 0) + 2);
            }
            unitID = msg.getUnitID();
            m_ByteOut.writeByte(unitID);
            fupnctionCode = msg.getFunctionCode();
            m_ByteOut.writeByte(fupnctionCode);
            if (message != null && message.length > 0)
                m_ByteOut.write(message);

            m_Output.write(m_ByteOut.toByteArray());
            m_Output.flush();
            int count = m_ByteOut.size();
            int requestlength = msg.getOutputLength();
            Log.d(Modbus.LOG_TAG_MODBUS, String.format("writeMessage transaction %d, protocol %d, function %d, count %d, outputLength %d", transactionID, protocolID, fupnctionCode, count, requestlength));
            Log.d(Modbus.LOG_TAG_MODBUS, "Sent: " + ModbusUtil.toHex(m_ByteOut.toByteArray()));
            Log.d(Modbus.LOG_TAG_MODBUS, "Sent (HexMessage): " + msg.getHexMessage());

            // write more sophisticated exception handling
        } catch (SocketException ex) {
            Log.d(Modbus.LOG_TAG_MODBUS, "writeMessage SocketException " + ex.getMessage());
            throw new ModbusIOException("I/O exception - failed to write.");
        } catch (Exception ex) {
            Log.d(Modbus.LOG_TAG_MODBUS, "writeMessage Exception " + ex.getMessage());
            throw new ModbusIOException("I/O exception - failed to write.");
        }
    }


    public ModbusResponse readResponse() throws ModbusIOException {

        try {

            ModbusResponse response;

            synchronized (m_ByteIn) {
                // use same buffer
                byte[] buffer = m_ByteIn.getBuffer();

                if (!headless) {
                    /*
					 * All Modbus TCP transactions start with 6 bytes. Get them.
					 */

                    if (m_Input.read(buffer, 0, 6) == -1)
                        throw new ModbusIOException(
                                "Premature end of stream (Header truncated).");

                    Log.d(Modbus.LOG_TAG_MODBUS, "Read header: " + ModbusUtil.toHex(buffer, 0, 6));
                    /*
					 * The transaction ID is the first word (offset 0) in the
					 * data that was just read. It will be echoed back to the
					 * requester.
					 *
					 * The protocol ID is the second word (offset 2) in the
					 * data. It should always be 0, but I don't check.
					 *
					 * The length of the payload is the third word (offset 4) in
					 * the data that was just read. That's what I need in order
					 * to read the rest of the response.
					 */
                    int transaction = ModbusUtil.registerToShort(buffer, 0);
                    int protocol = ModbusUtil.registerToShort(buffer, 2);
                    int count = ModbusUtil.registerToShort(buffer, 4);

                    if (m_Input.read(buffer, 6, count) == -1)
                        throw new ModbusIOException("Premature end of stream (Message truncated).");
                    m_ByteIn.reset(buffer, (6 + count));
                    m_ByteIn.reset();
                    m_ByteIn.skip(7);
                    int function = m_ByteIn.readUnsignedByte();
                    Log.d(Modbus.LOG_TAG_MODBUS, String.format("readResponse transaction %d, protocol %d, function %d, count %d", transaction, protocol, function, count));
                    Log.d(Modbus.LOG_TAG_MODBUS, "Read: " + ModbusUtil.toHex(buffer, 0, count + 6));
                    response = ModbusResponse.createModbusResponse(function);

					/*
					 * Rewind the input buffer, then read the data into the
					 * response.
					 */
                    m_ByteIn.reset();
                    response.readFrom(m_ByteIn);
                    response.setTransactionID(transaction);
                    response.setProtocolID(protocol);
                } else {
					/*
					 * This is a headless response. It has the same format as a
					 * RTU over Serial response.
					 */
                    int unit = m_Input.readByte();
                    int function = m_Input.readByte();
                    response = ModbusResponse.createModbusResponse(function);
                    response.setUnitID(unit);
                    response.setHeadless();
                    response.readData(m_Input);
					/*
					 * Now discard the CRC. Which hopefully wasn't needed
					 * because this is a TCP transport.
					 */
                    m_Input.readShort();
                }
            }
            return response;
        } catch (SocketTimeoutException ex) {
            Log.d(Modbus.LOG_TAG_MODBUS, String.format("Modbus: Timeout reading response."));
            throw new ModbusIOException("Timeout reading response");
        } catch (Exception ex) {
            Log.d(Modbus.LOG_TAG_MODBUS, String.format("Modbus: I/O exception - failed to read."));
            throw new ModbusIOException("I/O exception - failed to read.");
        }
    }

    /**
     * Prepares the input and output streams of this <tt>ModbusTCPTransport</tt>
     * instance based on the given socket.
     *
     * @param socket the socket used for communications.
     * @throws IOException if an I/O related error occurs.
     */
    private void prepareStreams(Socket socket) throws IOException {

		/*
		 * Close any open streams if I'm being called because a new socket was
		 * set to handle this transport.
		 */
        try {
            if (m_Input != null)
                m_Input.close();

            if (m_Output != null)
                m_Output.close();
        } catch (IOException x) {
            // Do nothing.
        }

        m_Input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

        m_Output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));


    }

    /**
     * Constructs a new <tt>ModbusTransport</tt> instance, for a given
     * <tt>Socket</tt>.
     * <p/>
     *
     * @param socket the <tt>Socket</tt> used for message transport.
     */
    public ModbusTCPTransport(Socket socket) {
        try {
            setSocket(socket);
            socket.setSoTimeout(m_Timeout);
        } catch (IOException ex) {
            Log.d(Modbus.LOG_TAG_MODBUS, "ModbusTCPTransport::Socket invalid.");
            throw new IllegalStateException("Socket invalid.");
        }
    }

    /**
     * readRequest -- Read a Modbus TCP encoded request. The packet has a 6 byte
     * header containing the protocol, transaction ID and length.
     *
     * @returns Modbus response for message
     * @throws ModbusIOException
     */
//	public ModbusRequest readRequest() throws ModbusIOException {
//
//	try {
//			ModbusRequest req = null;
//			m_ByteIn.reset();
//
//			synchronized (m_ByteIn) {
//				byte[] buffer = m_ByteIn.getBuffer();
//
//				if (!headless) {
//					if (m_Input.read(buffer, 0, 6) == -1)
//						throw new EOFException(
//								"Premature end of stream (Header truncated).");
//
//					int transaction = ModbusUtil.registerToShort(buffer, 0);
//					int protocol = ModbusUtil.registerToShort(buffer, 2);
//					int count = ModbusUtil.registerToShort(buffer, 4);
//                        Log.d(Modbus.LOG_TAG_MODBUS, String.format("readRequest transaction %d, protocol %d, count %d", transaction, protocol,  count));
//					if (m_Input.read(buffer, 6, count) == -1)
//						throw new ModbusIOException(
//								"Premature end of stream (Message truncated).");
//

//						Log.d(Modbus.LOG_TAG_MODBUS, "Read: " + ModbusUtil.toHex(buffer, 0, count + 6));
//
//					m_ByteIn.reset(buffer, (6 + count));
//					m_ByteIn.skip(6);
//
//					int unit = m_ByteIn.readByte();
//					int functionCode = m_ByteIn.readUnsignedByte();
//
//					m_ByteIn.reset();
//					req = ModbusRequest.createModbusRequest(functionCode);
//					req.setUnitID(unit);
//					req.setHeadless(false);
//					req.setTransactionID(transaction);
//					req.setProtocolID(protocol);
//					req.setDataLength(count);
//
//					req.readFrom(m_ByteIn);
//				} else {
//
//					/*
//					 * This is a headless request.
//					 */
//					int unit = m_Input.readByte();
//					int function = m_Input.readByte();
//
//					req = ModbusRequest.createModbusRequest(function);
//					req.setUnitID(unit);
//					req.setHeadless(true);
//
//					req.readData(m_Input);
//
//					/*
//					 * Discard the CRC. This is a TCP/IP connection, which has
//					 * proper error correction and recovery.
//					 */
//					m_Input.readShort();
//						Log.d(Modbus.LOG_TAG_MODBUS, "Read: "	+ req.getHexMessage());
//				}
//			}
//			return req;
//		} catch (EOFException eoex) {
//			throw new ModbusIOException("End of File", true);
//		} catch (SocketTimeoutException x) {
//			throw new ModbusIOException("Timeout reading request");
//		} catch (SocketException sockex) {
//			throw new ModbusIOException("Socket Exception", true);
//		} catch (Exception ex) {
//			throw new ModbusIOException("I/O exception - failed to read.");
//		}
//	}

}