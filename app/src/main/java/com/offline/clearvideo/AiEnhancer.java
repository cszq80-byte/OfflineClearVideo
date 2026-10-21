package com.offline.clearvideo;

import android.content.Context;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.DataType;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real pretrained FSRCNN x2 inference, CPU-only, no network or model download. */
final class AiEnhancer implements AutoCloseable {
    static final int TILE=64, PAD=8, CORE=TILE-2*PAD, OUT=TILE*2;
    private final ByteBuffer model;
    private final Interpreter interpreter;
    private final ByteBuffer input=ByteBuffer.allocateDirect(TILE*TILE*4).order(ByteOrder.nativeOrder());
    private final ByteBuffer output=ByteBuffer.allocateDirect(OUT*OUT*4).order(ByteOrder.nativeOrder());
    AiEnhancer(Context context) throws Exception {
        byte[] bytes;
        try (InputStream in=context.getAssets().open("fsrcnn_x2.tflite");
             ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int count;
            while((count=in.read(buffer))!=-1) out.write(buffer,0,count);
            bytes=out.toByteArray();
        }
        model=ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        model.put(bytes); model.rewind();
        Interpreter.Options options=new Interpreter.Options();
        options.setNumThreads(Math.min(4,Math.max(1,Runtime.getRuntime().availableProcessors()-1)));
        options.setUseXNNPACK(true);
        interpreter=new Interpreter(model,options);
        interpreter.allocateTensors();
        if (!Arrays.equals(interpreter.getInputTensor(0).shape(),new int[]{1,TILE,TILE,1}) ||
            !Arrays.equals(interpreter.getOutputTensor(0).shape(),new int[]{1,OUT,OUT,1}) ||
            interpreter.getInputTensor(0).dataType()!=DataType.FLOAT32 ||
            interpreter.getOutputTensor(0).dataType()!=DataType.FLOAT32) {
            interpreter.close(); throw new IllegalStateException("内置 AI 模型尺寸不匹配");
        }
    }
    void upscale(byte[] src,int w,int h,byte[] dst,boolean fullRange,AtomicBoolean canceled) throws InterruptedException {
        if(src.length<w*h || dst.length<w*h*4) throw new IllegalArgumentException("AI 图像缓冲区不足");
        float minimum=fullRange?0:16, span=fullRange?255:219;
        for(int sy=0;sy<h;sy+=CORE) for(int sx=0;sx<w;sx+=CORE) {
            if(canceled.get()) throw new InterruptedException("已取消");
            input.rewind();
            for(int y=0;y<TILE;y++) for(int x=0;x<TILE;x++) {
                int px=Math.max(0,Math.min(w-1,sx+x-PAD));
                int py=Math.max(0,Math.min(h-1,sy+y-PAD));
                input.putFloat(Math.max(0f,Math.min(1f,((src[py*w+px]&255)-minimum)/span)));
            }
            input.rewind(); output.rewind(); interpreter.run(input,output);
            int cw=Math.min(CORE,w-sx)*2, ch=Math.min(CORE,h-sy)*2;
            for(int y=0;y<ch;y++) for(int x=0;x<cw;x++) {
                float value=output.getFloat(((y+PAD*2)*OUT+x+PAD*2)*4);
                if(!Float.isFinite(value)) throw new IllegalStateException("AI 推理产生无效数值");
                int p=Math.round(minimum+span*Math.max(0f,Math.min(1f,value)));
                dst[(sy*2+y)*w*2+sx*2+x]=(byte)Math.max(0,Math.min(255,p));
            }
        }
    }
    @Override public void close(){interpreter.close();}
}
