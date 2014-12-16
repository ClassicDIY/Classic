/*
 * Copyright (c) 2014. FarrelltonSolar
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ca.farrelltonsolar.classic;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;

import ca.farrelltonsolar.j2modlite.ModbusException;

/**
 * Created by Graham on 08/12/2014.
 */
public class UDPListener extends Service {

    final Object lock = new Object();
    private final IBinder mBinder = new UDPListenerServiceBinder();
    private static Gson GSON = new Gson();
    private ListenerThread mListener;

    public UDPListener() {
    }

    public class UDPListenerServiceBinder extends Binder {
        UDPListener getService() {
            return UDPListener.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }


    class ListenerThread extends Thread {
        private boolean running;
        private DatagramSocket socket;
        private byte[] buffer = new byte[16];
        private DatagramPacket packet;
        private ArrayList<InetSocketAddress> alreadyFoundList = new ArrayList<>();

        public void addToAlreadyFoundList(InetSocketAddress address) {
            synchronized (lock) {
                alreadyFoundList.add(address);
            }
        }

        private boolean hasAddressAlreadyBeenFound(InetSocketAddress address) {
            boolean rVal = false;
            synchronized (lock) {
                for (InetSocketAddress cc : alreadyFoundList) {
                    if (cc.equals(address)) {
                        rVal = true;
                        break;
                    }
                }
            }
            return rVal;
        }

        public void removeFromAlreadyFoundList(InetSocketAddress address) {
            synchronized (lock) {
                alreadyFoundList.remove(address);
            }
        }

        public ListenerThread(ArrayList<InetSocketAddress> current) {
            try {
                alreadyFoundList = current;
                packet = new DatagramPacket(buffer, buffer.length);
                socket = new DatagramSocket(Constants.CLASSIC_UDP_PORT);
                socket.setSoTimeout(2000);
            } catch (IOException ex) {
                Log.w(getClass().getName(), "Listener ctor: " + ex.getMessage());
                throw new RuntimeException("Creating datagram ListenerThread failed", ex);
            }
        }

        private boolean GetRunning() {
            synchronized (lock) {
                return running;
            }
        }

        public void SetRunning(boolean state) {
            synchronized (lock) {
                running = state;
                if (state == false && socket != null) {
                    socket.close();
                    socket.disconnect();
                }
            }
        }

        @Override
        public void run() {
            SetRunning(true);
            int sleepTime = 1000;
            try {
                do {
                    try {
                        socket.receive(packet);
                        byte[] data = packet.getData();
                        byte[] addr = new byte[4];
                        addr[0] = data[0];
                        addr[1] = data[1];
                        addr[2] = data[2];
                        addr[3] = data[3];
                        InetAddress address = InetAddress.getByAddress(addr);
                        int port = ((int) data[4] & 0xff);
                        port += ((long) data[5] & 0xffL) << (8);
                        InetSocketAddress socketAddress = new InetSocketAddress(address, port);
                        if (hasAddressAlreadyBeenFound(socketAddress) == false) {
                            Log.d(getClass().getName(), "Found new classic at address: " + address + " port: " + port);
                            addToAlreadyFoundList(socketAddress);
                            Runnable r = new NamerThread(socketAddress, this);
                            new Thread(r).start();
                        }
                    } catch (SocketTimeoutException iox) {

                    } catch (IOException ex) {
                        if (socket != null && socket.isClosed()) {
                            break;
                        }
                        Log.w(getClass().getName(), "IOException: " + ex.getMessage());
                    }
                    Thread.sleep(sleepTime);
                } while (GetRunning());
            } catch (Exception e) {
                Log.w(getClass().getName(), "mListener Exception: " + e.toString());
            } finally {
                socket.close();
                socket.disconnect();
                Log.d(getClass().getName(), "closed socket and disconnected");
            }
            Log.d(getClass().getName(), "mListener exiting");
        }

        public class NamerThread implements Runnable {
            InetSocketAddress socketAddress;
            ListenerThread container;

            public NamerThread(InetSocketAddress val, ListenerThread c) {
                socketAddress = val;
                container = c;
            }

            @Override
            public void run() {
                ModbusTask modbus = new ModbusTask(socketAddress, UDPListener.this);
                if (modbus.connect()) {
                    try {
                        String name = modbus.getInfo();
                        Log.d(getClass().getName(), "And it's name is: " + name);
                        LocalBroadcastManager broadcaster = LocalBroadcastManager.getInstance(UDPListener.this);
                        ChargeController cc = new ChargeController(socketAddress.getAddress().getHostAddress(), name, socketAddress.getPort());
                        Intent pkg = new Intent("ca.farrelltonsolar.classic.AddChargeController");
                        pkg.putExtra("ChargeController", GSON.toJson(cc));
                        broadcaster.sendBroadcast(pkg);

                    } catch (ModbusException e) {
                        Log.d(getClass().getName(), "Failed to get unit info" + e.getMessage());
                        removeFromAlreadyFoundList(socketAddress);
                    } finally {
                        modbus.disconnect();
                    }
                }
            }
        }


    }

    public void listen(ArrayList<InetSocketAddress> alreadyFoundList) {
        stopListening();
        mListener = new ListenerThread(alreadyFoundList);
        mListener.start();
        Log.d(getClass().getName(), "UDP Listener running");
    }

    public void stopListening() {
        if (mListener != null) {
            mListener.SetRunning(false);
            mListener = null;
            Log.d(getClass().getName(), "stopListening");
        }
    }

    @Override
    public void onDestroy() {
        stopListening();
        super.onDestroy();
        Log.d(getClass().getName(), "onDestroy");
    }
}
