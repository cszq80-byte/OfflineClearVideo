package com.offline.clearvideo;

import android.content.Context;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-quality RGB super-resolution using pretrained Real-ESRGAN general x4v3.
 * Processes 96x96 patches with 12 pixel margins. The 4x network output is
 * carefully downsampled to 2x to limit encoder memory and file size.
 * All inference is local; the model is bundled into the APK at build time.
 */
final class RealEsrganEnhancer implements AutoCloseable {
    private static final int TILE=96,PAD=12,CORE=TILE-PAD*2,SCALE=4;
    private static final int PLANE=TILE*TILE;
    private static final int OUTPUT_WIDTH=TILE*SCALE,OUTPUT_PLANE=OUTPUT_WIDTH*OUTPUT_WIDTH;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final FloatBuffer input=ByteBuffer.allocateDirect(3*PLANE*4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();

    RealEsrganEnhancer(Context context) throws Exception {
        byte[] bytes;
        try(InputStream in=context.getAssets().open("realesr-general-x4v3.onnx");
            ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[16*1024];int n;
            while((n=in.read(buf))>=0)out.write(buf,0,n);
            bytes=out.toByteArray();
        }
        if(bytes.length<1000000)throw new IllegalStateException("Real-ESRGAN 模型不完整");
        env=OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options=new OrtSession.SessionOptions();
        try {
            options.setIntraOpNumThreads(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)));
            options.setInterOpNumThreads(1);
            session=env.createSession(bytes,options);
        } finally {options.close();}
        if(session.getInputNames().size()!=1 || session.getOutputNames().size()!=1) {
            session.close();throw new IllegalStateException("Real-ESRGAN 模型输入输出不符合预期");
        }
        inputName=session.getInputNames().iterator().next();
    }
    void upscale(YuvEnhancer.Scratch b,boolean fullRange,boolean bt709,
                 AtomicBoolean canceled) throws Exception {
        if(b.dw!=b.sw*2||b.dh!=b.sh*2)
            throw new IllegalArgumentException("Real-ESRGAN 需要 2 倍输出尺寸");
        final int w=b.sw,h=b.sh;
        final float kr=bt709?0.2126f:0.299f,kb=bt709?0.0722f:0.114f,kg=1-kr-kb;
        final float rv=bt709?1.5748f:1.402f;
        final float gu=bt709?0.1873f:0.344136f;
        final float gv=bt709?0.4681f:0.714136f;
        final float bu=bt709?1.8556f:1.772f;
        final float chromaScale=fullRange?1f:255f/224f;
        final float[] pixel=new float[3];

        for(int sy=0;sy<h;sy+=CORE) {
            for(int sx=0;sx<w;sx+=CORE) {
                if(canceled.get())throw new InterruptedException("已取消");
                // NCHW, 0..1 RGB. Input YUV420 chroma is nearest-neighbour expanded.
                for(int iy=0;iy<TILE;iy++)for(int ix=0;ix<TILE;ix++) {
                    int x=clamp(sx+ix-PAD,0,w-1),y=clamp(sy+iy-PAD,0,h-1);
                    float lum=b.y[y*w+x]&255;
                    if(!fullRange)lum=(lum-16)*255f/219f;
                    int ci=(y/2)*((w+1)/2)+(x/2);
                    float cb=((b.u[ci]&255)-128)*chromaScale,cr=((b.v[ci]&255)-128)*chromaScale;
                    int off=iy*TILE+ix;
                    input.put(off,clamp01((lum+rv*cr)/255f));
                    input.put(PLANE+off,clamp01((lum-gu*cb-gv*cr)/255f));
                    input.put(PLANE*2+off,clamp01((lum+bu*cb)/255f));
                }
                input.rewind();
                try(OnnxTensor tensor=OnnxTensor.createTensor(env,input,new long[]{1,3,TILE,TILE});
                    OrtSession.Result result=session.run(Collections.singletonMap(inputName,tensor))) {
                    OnnxTensor output=(OnnxTensor)result.get(0);
                    long[] shape=((ai.onnxruntime.TensorInfo)output.getInfo()).getShape();
                    if(shape.length!=4||shape[0]!=1||shape[1]!=3||
                       shape[2]!=OUTPUT_WIDTH||shape[3]!=OUTPUT_WIDTH)
                        throw new IllegalStateException("Real-ESRGAN 模型输出尺寸错误");
                    FloatBuffer rgb=output.getFloatBuffer();
                    int cw=Math.min(CORE,w-sx),ch=Math.min(CORE,h-sy);
                    // Every 2x2 group of 2x pixels maps to one chroma pixel.
                    for(int dy=0;dy<ch*2;dy+=2)for(int dx=0;dx<cw*2;dx+=2) {
                        float sumR=0,sumG=0,sumB=0;
                        for(int py=0;py<2;py++)for(int px=0;px<2;px++) {
                            int xx=dx+px, yy=dy+py;
                            int ox=(PAD*SCALE+xx*2),oy=(PAD*SCALE+yy*2);
                            sample2x2(rgb,oy,ox,pixel);
                            float r=pixel[0],g=pixel[1],bl=pixel[2];
                            int gx=sx*2+xx,gy=sy*2+yy;
                            float yLinear=kr*r+kg*g+kb*bl;
                            int y8=Math.round(fullRange?yLinear:16f+yLinear*219f/255f);
                            b.oy[gy*b.dw+gx]=(byte)clamp(y8,0,255);
                            sumR+=r;sumG+=g;sumB+=bl;
                        }
                        float avgR=sumR*.25f,avgG=sumG*.25f,avgB=sumB*.25f;
                        float avgY=kr*avgR+kg*avgG+kb*avgB;
                        float chromaRange=fullRange?1f:224f/255f;
                        int u8=Math.round(128+(avgB-avgY)/(2*(1-kb))*chromaRange);
                        int v8=Math.round(128+(avgR-avgY)/(2*(1-kr))*chromaRange);
                        int uv=(sy+dy/2)*((b.dw+1)/2)+(sx+dx/2);
                        b.ou[uv]=(byte)clamp(u8,0,255);
                        b.ov[uv]=(byte)clamp(v8,0,255);
                    }
                }
            }
        }
    }
    private static void sample2x2(FloatBuffer rgb,int y,int x,float[] out) {
        int n=OUTPUT_PLANE;
        int a=y*OUTPUT_WIDTH+x;
        for(int c=0;c<3;c++) {
            int base=c*n+a;
            float v=(rgb.get(base)+rgb.get(base+1)+rgb.get(base+OUTPUT_WIDTH)+
                     rgb.get(base+OUTPUT_WIDTH+1))*0.25f;
            if(!Float.isFinite(v))throw new IllegalStateException("AI 模型产生了无效颜色");
            out[c]=clamp01(v)*255f;
        }
    }
    private static int clamp(int v,int low,int high){return Math.max(low,Math.min(high,v));}
    private static float clamp01(float v){return Math.max(0f,Math.min(1f,v));}
    @Override public void close(){session.close();}
}
