package com.bluesky.test;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.bluesky.common.UDPService;
import com.bluesky.protocol.CallData;
import com.bluesky.protocol.CallInit;
import com.bluesky.protocol.CallTerm;

public class Main {
    public static void main(String[] args) {



        Thread mThread = new Thread( new ExecutionHelper() );

        mThread.run();

    }


    public static class ExecutionHelper implements Runnable {

        @Override
        public void run(){
            UDPService.Configuration udpSvcConfig = new UDPService.Configuration();
            udpSvcConfig.addrLocal = new InetSocketAddress(GlobalConstants.TRUNK_CENTER_PORT);
            mUdpService = new UDPService(udpSvcConfig);
            mUdpService.startService();
            try {
                Thread.sleep(1000);
            } catch (Exception e){
                LOGGER.info(TAG + " exception in sleep");
            }


            long time_first = System.nanoTime();
            mCallInfo = new CallInformation();
            mCallInfo.mTargetId = 0x111;
            mCallInfo.mSenderIpPort = new InetSocketAddress("192.168.0.104", 32001);

            for(int i=0; i<3; ++i){
                long time_prior_send = System.nanoTime();
                sendCallInit();
                long time_now = System.nanoTime();

                try {
                    Thread.sleep(
                            (GlobalConstants.CALL_PACKET_INTERVAL *(i+1) - (time_now - time_first)/(1000*1000)));
                } catch (Exception e){
                    LOGGER.info(TAG + " exception in sleep");
                }
            }

            int i = 3;
            while(sendCallData()){
                long time_now = System.nanoTime();
                try {
                    Thread.sleep(
                            (GlobalConstants.CALL_PACKET_INTERVAL * (i+1) - (time_now - time_first)/(1000*1000)));
                } catch (Exception e){
                    LOGGER.info(TAG + " exception in sleep");
                }

                ++i;
            }

            for(int j=0; j<3; ++j){
                sendCallTerm();
                try {
                    Thread.sleep(GlobalConstants.CALL_PACKET_INTERVAL);
                } catch (Exception e){
                    LOGGER.info(TAG + " exception in sleep:" + e);
                }
            }

        }


        private void sendCallInit() {
            if(mHasSignaling){
                mTxSeq = (short) (new Random()).nextInt();
                CallInit preamble = new CallInit(mCallInfo.mTargetId, GlobalConstants.SUID_TRUNK_MANAGER);
                preamble.setSequence(++mTxSeq);
                ByteBuffer payload = ByteBuffer.allocate(preamble.getSize());
                preamble.serialize(payload);
                try {
                    mUdpService.send(mCallInfo.mSenderIpPort, payload);
                } catch (Exception e){
                    LOGGER.warning(TAG + " exception in send:" + e);
                }
            }

            if (mInStream == null) {
                try {
                    mInStream = new BufferedInputStream(new FileInputStream(AUDIO_FILE_NAME));
                    mInStream.skip(AMR_FILE_HEADER_SINGLE_CHANNEL.length());
                } catch (Exception e) {
                    LOGGER.warning(TAG + "failed to open: " + AUDIO_FILE_NAME + ", " + e);
                }
            }
        }

        private boolean sendCallData() {
            byte[] buffer = new byte[GlobalConstants.COMPRESSED_20MS_AUDIO_SIZE];
            int sz;
            try {
                sz = mInStream.read(buffer, 0, GlobalConstants.COMPRESSED_20MS_AUDIO_SIZE);
            } catch (Exception e) {
                LOGGER.warning(TAG + "failed to read:" + e);
                return false;
            }

            if (sz == -1) {
                LOGGER.info(TAG + "end of audio file");
                return false;
            }

            ByteBuffer payload;

            if( mHasSignaling ) {
                ByteBuffer buf = ByteBuffer.wrap(buffer, 0, sz);
                LOGGER.info("TX[" + (++mProtoCount) + "]:" +
                        buf.getShort(2) + ":" + buf.getShort(3));
                CallData callData = new CallData(
                        mCallInfo.mTargetId,
                        GlobalConstants.SUID_TRUNK_MANAGER,
                        ++mAudioSeq,
                        buf);
                callData.setSequence(++mTxSeq);
                payload = ByteBuffer.allocate(callData.getSize());
                callData.serialize(payload);
            } else {
                payload = ByteBuffer.wrap(buffer);
                LOGGER.info("TX[" + (++mProtoCount) + "]:" +
                    payload.getShort(2) + ":" + payload.getShort(3) + "==>" +
                        buffer[4] + ":" + buffer[5]);
            }
            try {
                mUdpService.send(mCallInfo.mSenderIpPort, payload);
            } catch (Exception e){
                LOGGER.warning(TAG + " exception in send:" + e);
            }

            return true;
        }

        private void sendCallTerm() {

            if (mInStream != null) {
                try {
                    mInStream.close();
                    mInStream = null;
                } catch (Exception e) {
                    LOGGER.warning(TAG + "error happened in close " + e);
                }
            }
            if(mHasSignaling) {
                CallTerm callTerm = new CallTerm(
                        mCallInfo.mTargetId,
                        GlobalConstants.SUID_TRUNK_MANAGER,
                        ++mAudioSeq
                );
                callTerm.setSequence(++mTxSeq);
                ByteBuffer payload = ByteBuffer.allocate(callTerm.getSize());
                callTerm.serialize(payload);
                try {
                    mUdpService.send(mCallInfo.mSenderIpPort, payload);
                } catch (Exception e) {
                    LOGGER.warning(TAG + " exception in send:" + e);
                }
            }

        }

        CallInformation     mCallInfo;
        short               mTxSeq;
        short               mAudioSeq = 0;
        short               mProtoCount = 0;

        BufferedInputStream  mInStream;
        UDPService mUdpService;

        static final String AUDIO_FILE_NAME = "audio.amr";
        static final String AMR_FILE_HEADER_SINGLE_CHANNEL = "#!AMR\n";
        static final String TAG = "EchoingCP: ";
        static final Logger LOGGER  = Logger.getLogger(UDPService.class.getName());

        boolean             mHasSignaling = true;
    }

    public static class CallInformation {
        InetSocketAddress   mSenderIpPort;

        public short mSequence;
        public long mTargetId;
        public long mSuid;
        public short       mAudioSeq;
    }

    public static class GlobalConstants{
        final static String TAG = "TrunkManager";
        public static final int TRUNK_CENTER_PORT   = 32000;
        public static final int INIT_SEQ_NUMBER     = 12345;

        /** call parameters */
        public static final int CALL_FLYWHEEL_PERIOD    = 1500;  // return to idle if no rxed packet
        public static final int CALL_HANG_PERIOD        = 10000; //
        public static final int CALL_PACKET_INTERVAL    = 20;    // 20ms
        public static final int CALL_PREAMBLE_NUMBER    = 3;
        public static final int CALL_TERM_NUMBER        = -3;

        public static final int COMPRESSED_20MS_AUDIO_SIZE  = 20;

        /** call info for faked echo */
        public static final long    SUID_TRUNK_MANAGER  = 1;
    }





}
