package com.offline.clearvideo;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entire pipeline runs on-device. Decoder -> optional trained FSRCNN -> AVC encoder -> MP4. */
final class VideoProcessor {
    interface Listener { void progress(int percent,String message); }
    static final class Result {
        final int width,height,frames;
        final boolean retainedAudio,audioPresent;
        Result(int w,int h,int f,boolean audio,boolean present) {
            width=w;height=h;frames=f;retainedAudio=audio;audioPresent=present;
        }
    }
    static Result process(Context context,Uri source,Uri destination,int mode,
                          Listener listener,AtomicBoolean canceled) throws Exception {
        if(source.equals(destination))throw new IllegalArgumentException("不能覆盖原视频");
        if(canceled.get())throw new InterruptedException("已取消");
        MediaExtractor vx=null,ax=null;
        MediaCodec decoder=null,encoder=null;
        MediaMuxer muxer=null;
        ParcelFileDescriptor fd=null;
        AiEnhancer ai=null;
        boolean muxStarted=false;
        try {
            emit(listener,0,"读取本地视频信息…");
            vx=new MediaExtractor();vx.setDataSource(context,source,null);
            int vt=-1,at=-1;MediaFormat vf=null,af=null;boolean audioPresent=false;
            for(int i=0;i<vx.getTrackCount();i++) {
                MediaFormat f=vx.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime==null)continue;
                if(mime.startsWith("video/")&&vt<0){vt=i;vf=f;}
                if(mime.startsWith("audio/")) {
                    audioPresent=true;
                    if(at<0&&(mime.equals("audio/mp4a-latm")||mime.equals("audio/3gpp")||mime.equals("audio/amr-wb"))) {
                        at=i;af=f;
                    }
                }
            }
            if(vt<0||vf==null)throw new IllegalArgumentException("文件中没有视频轨道");
            int transfer=integer(vf,MediaFormat.KEY_COLOR_TRANSFER,3);
            if(transfer==6||transfer==7||vf.containsKey("hdr-static-info"))
                throw new IllegalArgumentException("本版不支持 HDR 视频，请使用 SDR 视频");
            int sw=integer(vf,MediaFormat.KEY_WIDTH,0),sh=integer(vf,MediaFormat.KEY_HEIGHT,0);
            if(vf.containsKey("crop-right"))
                sw=integer(vf,"crop-right",sw-1)-integer(vf,"crop-left",0)+1;
            if(vf.containsKey("crop-bottom"))
                sh=integer(vf,"crop-bottom",sh-1)-integer(vf,"crop-top",0)+1;
            int[] size=OutputProfile.size(sw,sh,mode);int w=size[0],h=size[1];
            long duration=vf.containsKey(MediaFormat.KEY_DURATION)?vf.getLong(MediaFormat.KEY_DURATION):-1;
            int fps=Math.max(1,Math.min(120,integer(vf,MediaFormat.KEY_FRAME_RATE,30)));
            int rotation=integer(vf,MediaFormat.KEY_ROTATION,0);
            if(rotation!=90&&rotation!=180&&rotation!=270)rotation=0;
            boolean fullRange=integer(vf,MediaFormat.KEY_COLOR_RANGE,2)==1;
            if(mode==OutputProfile.AI) {
                emit(listener,0,"加载 APK 内置的 FSRCNN 神经网络模型…");
                ai=new AiEnhancer(context);
            }
            MediaFormat ef=MediaFormat.createVideoFormat("video/avc",w,h);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            ef.setInteger(MediaFormat.KEY_BIT_RATE,OutputProfile.bitrate(w,h,fps));
            ef.setInteger(MediaFormat.KEY_FRAME_RATE,fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);
            for(String key:new String[]{MediaFormat.KEY_COLOR_STANDARD,MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.KEY_COLOR_RANGE})
                if(vf.containsKey(key))ef.setInteger(key,integer(vf,key,0));
            Exception lastError=null;
            for(String name:encoders(w,h,fps)) {
                MediaCodec candidate=null;
                try {
                    candidate=MediaCodec.createByCodecName(name);
                    candidate.configure(ef,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
                    candidate.start();encoder=candidate;break;
                } catch(Exception e) {
                    lastError=e;if(candidate!=null){try{candidate.stop();}catch(Exception ignored){}try{candidate.release();}catch(Exception ignored){}}
                }
            }
            if(encoder==null)throw new IllegalStateException("手机不能以此分辨率编码，请降低档位",lastError);
            vx.selectTrack(vt);
            String mime=vf.getString(MediaFormat.KEY_MIME);
            decoder=MediaCodec.createDecoderByType(mime);
            try {
                MediaFormat df=vx.getTrackFormat(vt);
                df.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                decoder.configure(df,null,null,0);
            }catch(Exception e) {
                decoder.release();decoder=MediaCodec.createDecoderByType(mime);
                decoder.configure(vf,null,null,0);
            }
            decoder.start();
            if(canceled.get())throw new InterruptedException("已取消");
            fd=context.getContentResolver().openFileDescriptor(destination,"rw");
            if(fd==null)throw new IllegalStateException("无法打开保存文件");
            muxer=new MediaMuxer(fd.getFileDescriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(rotation);
            int ma=-1;
            if(af!=null) {
                try {
                    ax=new MediaExtractor();ax.setDataSource(context,source,null);ax.selectTrack(at);
                    ma=muxer.addTrack(af);
                } catch(Exception unsupported) {
                    if(ax!=null){ax.release();ax=null;}ma=-1;
                }
            }
            boolean retained=ma>=0;
            int audioCapacity=af==null?0:Math.max(1048576,Math.min(8388608,integer(af,MediaFormat.KEY_MAX_INPUT_SIZE,1048576)));
            ByteBuffer audio=retained?ByteBuffer.allocateDirect(audioCapacity):null;
            MediaCodec.BufferInfo audioInfo=new MediaCodec.BufferInfo(),di=new MediaCodec.BufferInfo(),ei=new MediaCodec.BufferInfo();
            YuvEnhancer.Scratch scratch=null;
            boolean sourceEnd=false,decoderEnd=false,encoderEos=false,encoderEnd=false,pendingEos=false;
            int pending=-1,mv=-1,frames=0;
            long lastPts=0,lastFramePts=-1,lastProgress=0,lastMotion=SystemClock.elapsedRealtime();
            emit(listener,0,"正在本地逐帧增强，请保持 App 在前台…");
            while(!encoderEnd) {
                if(canceled.get())throw new InterruptedException("已取消");
                boolean moved=false;
                if(!sourceEnd) {
                    int index=decoder.dequeueInputBuffer(0);
                    if(index>=0) {
                        ByteBuffer b=decoder.getInputBuffer(index);
                        if(b==null)throw new IllegalStateException("解码输入缓冲区不可用");
                        b.clear();int n=vx.readSampleData(b,0);
                        if(n<0) {
                            decoder.queueInputBuffer(index,0,0,lastPts+1,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sourceEnd=true;
                        }else {
                            if((vx.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)
                                throw new IllegalArgumentException("不支持加密视频");
                            long pts=Math.max(0,vx.getSampleTime());
                            decoder.queueInputBuffer(index,0,n,pts,0);
                            vx.advance();lastPts=Math.max(lastPts,pts);
                        }
                        moved=true;
                    }
                }
                if(!decoderEnd&&pending<0) {
                    int index=decoder.dequeueOutputBuffer(di,1000);
                    if(index>=0){pending=index;pendingEos=(di.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;moved=true;}
                    else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        fullRange=integer(decoder.getOutputFormat(),MediaFormat.KEY_COLOR_RANGE,fullRange?1:2)==1;
                        moved=true;
                    }
                }
                if(pending>=0) {
                    if(di.size==0) {
                        decoder.releaseOutputBuffer(pending,false);pending=-1;
                        if(pendingEos)decoderEnd=true;moved=true;
                    }else {
                        int index=encoder.dequeueInputBuffer(0);
                        if(index>=0) {
                            Image src=decoder.getOutputImage(pending),dst=encoder.getInputImage(index);
                            try {
                                if(src==null||dst==null)throw new IllegalStateException("本机不支持逐帧 YUV 图像访问");
                                if(scratch==null)
                                    scratch=new YuvEnhancer.Scratch(src.getCropRect().width(),src.getCropRect().height(),w,h);
                                YuvEnhancer.transform(src,dst,scratch,ai,fullRange,canceled);
                            }finally {if(src!=null)src.close();if(dst!=null)dst.close();}
                            long pts=Math.max(lastFramePts+1,Math.max(0,di.presentationTimeUs));lastFramePts=pts;
                            encoder.queueInputBuffer(index,0,w*h*3/2,pts,0);
                            decoder.releaseOutputBuffer(pending,false);pending=-1;
                            if(pendingEos)decoderEnd=true;
                            frames++;moved=true;
                            long now=SystemClock.elapsedRealtime();
                            if(now-lastProgress>=400) {
                                int percent=duration>0?(int)Math.min(98,100L*pts/duration):0;
                                emit(listener,percent,(mode==OutputProfile.AI?"AI 2 倍":"普通增强")+" · "+(pts/1000000)+" 秒 / "+frames+" 帧 · "+w+"×"+h);
                                lastProgress=now;
                            }
                        }
                    }
                }
                if(decoderEnd&&pending<0&&!encoderEos) {
                    int index=encoder.dequeueInputBuffer(0);
                    if(index>=0) {
                        encoder.queueInputBuffer(index,0,0,Math.max(lastPts,lastFramePts)+1000000/fps,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encoderEos=true;moved=true;
                    }
                }
                int index=encoder.dequeueOutputBuffer(ei,5000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if(muxStarted)throw new IllegalStateException("编码器重复改变输出格式");
                    mv=muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();muxStarted=true;moved=true;
                }else if(index>=0) {
                    if(ei.size>0&&(ei.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                        if(!muxStarted)throw new IllegalStateException("输出轨道未就绪");
                        ByteBuffer b=encoder.getOutputBuffer(index);
                        if(b==null)throw new IllegalStateException("编码输出缓冲区为空");
                        b.position(ei.offset);b.limit(ei.offset+ei.size);
                        if(retained)copyAudio(ax,muxer,ma,audio,audioInfo,ei.presentationTimeUs+150000,canceled);
                        muxer.writeSampleData(mv,b,ei);
                    }
                    encoderEnd=(ei.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    encoder.releaseOutputBuffer(index,false);moved=true;
                }
                if(moved)lastMotion=SystemClock.elapsedRealtime();
                else if(SystemClock.elapsedRealtime()-lastMotion>60000)
                    throw new IllegalStateException("视频编解码器长时间无响应，请尝试降低档位");
            }
            if(!muxStarted||frames==0)throw new IllegalStateException("没有生成视频帧");
            if(retained){emit(listener,99,"封装原始声音…");copyAudio(ax,muxer,ma,audio,audioInfo,Long.MAX_VALUE,canceled);}
            if(canceled.get())throw new InterruptedException("已取消");
            muxer.stop();muxStarted=false;
            emit(listener,100,"文件已保存");
            return new Result(w,h,frames,retained,audioPresent);
        }finally {
            if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}try{decoder.release();}catch(Exception ignored){}}
            if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}try{encoder.release();}catch(Exception ignored){}}
            if(vx!=null)try{vx.release();}catch(Exception ignored){}
            if(ax!=null)try{ax.release();}catch(Exception ignored){}
            if(muxer!=null){if(muxStarted)try{muxer.stop();}catch(Exception ignored){}try{muxer.release();}catch(Exception ignored){}}
            if(fd!=null)try{fd.close();}catch(Exception ignored){}
            if(ai!=null)try{ai.close();}catch(Exception ignored){}
        }
    }
    private static void copyAudio(MediaExtractor x,MediaMuxer m,int track,ByteBuffer b,
                                  MediaCodec.BufferInfo info,long until,AtomicBoolean cancel) throws Exception {
        while(true) {
            if(cancel.get())throw new InterruptedException("已取消");
            long pts=x.getSampleTime();if(pts<0||pts>until)return;
            if((x.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)
                throw new IllegalArgumentException("音轨已加密");
            b.clear();int n=x.readSampleData(b,0);if(n<0)return;
            info.set(0,n,pts,(x.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
            b.position(0);b.limit(n);m.writeSampleData(track,b,info);x.advance();
        }
    }
    private static int integer(MediaFormat f,String key,int fallback) {
        if(!f.containsKey(key))return fallback;
        try{return f.getInteger(key);}catch(Exception e) {
            try{return Math.round(f.getFloat(key));}catch(Exception ignored){return fallback;}
        }
    }
    private static List<String> encoders(int w,int h,int fps) {
        List<String> hardware=new ArrayList<>(),software=new ArrayList<>();
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if(!info.isEncoder())continue;
            try {
                MediaCodecInfo.CodecCapabilities c=info.getCapabilitiesForType("video/avc");
                boolean flexible=false;
                for(int f:c.colorFormats)
                    if(f==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)flexible=true;
                if(!flexible||c.getVideoCapabilities()==null||
                   !c.getVideoCapabilities().areSizeAndRateSupported(w,h,fps))continue;
                if(Build.VERSION.SDK_INT>=29&&info.isHardwareAccelerated())hardware.add(info.getName());
                else software.add(info.getName());
            }catch(Exception ignored){}
        }
        hardware.addAll(software);return hardware;
    }
    private static void emit(Listener l,int p,String message){if(l!=null)l.progress(p,message);}
}
