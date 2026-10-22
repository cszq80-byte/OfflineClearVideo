package com.offline.clearvideo;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plane-stride-aware YUV conversion; ordinary filtering and AI are separate paths. */
final class YuvEnhancer {
    static final class Scratch {
        final int sw,sh,dw,dh;
        final byte[] y,u,v,detail,oy,ou,ov;
        byte[] baseY,baseU,baseV; // allocated only on first natural/strong frame, then reused
        Scratch(int sw,int sh,int dw,int dh) {
            this.sw=sw;this.sh=sh;this.dw=dw;this.dh=dh;
            y=new byte[sw*sh];detail=new byte[y.length];
            u=new byte[((sw+1)/2)*((sh+1)/2)];v=new byte[u.length];
            oy=new byte[dw*dh];ou=new byte[((dw+1)/2)*((dh+1)/2)];ov=new byte[ou.length];
        }
    }
    static void transform(Image source,Image target,Scratch b,AiEnhancer ai,boolean fullRange,AtomicBoolean cancel) throws InterruptedException {
        if(source.getFormat()!=ImageFormat.YUV_420_888 || target.getFormat()!=ImageFormat.YUV_420_888)
            throw new IllegalArgumentException("本机编解码器不支持 YUV 图像访问");
        Rect s=source.getCropRect(),d=target.getCropRect();
        if(s.width()!=b.sw || s.height()!=b.sh || d.width()<b.dw || d.height()<b.dh)
            throw new IllegalArgumentException("视频帧尺寸变化或编码缓冲区过小");
        Image.Plane[] p=source.getPlanes(),q=target.getPlanes();
        read(p[0],s.left,s.top,b.sw,b.sh,b.y);
        int cw=(b.sw+1)/2,ch=(b.sh+1)/2,ow=(b.dw+1)/2,oh=(b.dh+1)/2;
        read(p[1],s.left/2,s.top/2,cw,ch,b.u);read(p[2],s.left/2,s.top/2,cw,ch,b.v);
        if(ai!=null) {
            if(b.dw!=b.sw*2 || b.dh!=b.sh*2) throw new IllegalArgumentException("AI 需要精确 2 倍输出尺寸");
            ai.upscale(b.y,b.sw,b.sh,b.oy,fullRange,cancel);
        } else {
            enhance(b.y,b.detail,b.sw,b.sh);resize(b.detail,b.sw,b.sh,b.oy,b.dw,b.dh);
        }
        resize(b.u,cw,ch,b.ou,ow,oh);resize(b.v,cw,ch,b.ov,ow,oh);
        write(q[0],d.left,d.top,b.dw,b.dh,b.oy);
        write(q[1],d.left/2,d.top/2,ow,oh,b.ou);write(q[2],d.left/2,d.top/2,ow,oh,b.ov);
    }
    /** New full-RGB neural path, deliberately separate from legacy FSRCNN. */
    static void transformHQ(Image source,Image target,Scratch b,
                            RealEsrganEnhancer ai,boolean fullRange,boolean bt709,int detailStrength,
                            AtomicBoolean cancel) throws Exception {
        if(source.getFormat()!=ImageFormat.YUV_420_888 ||
           target.getFormat()!=ImageFormat.YUV_420_888)
            throw new IllegalArgumentException("手机编解码器没有可读取的 YUV 图像");
        Rect s=source.getCropRect(),d=target.getCropRect();
        if(s.width()!=b.sw||s.height()!=b.sh||d.width()<b.dw||d.height()<b.dh)
            throw new IllegalArgumentException("视频帧尺寸变化");
        Image.Plane[] p=source.getPlanes(),q=target.getPlanes();
        read(p[0],s.left,s.top,b.sw,b.sh,b.y);
        int cw=(b.sw+1)/2,ch=(b.sh+1)/2;
        read(p[1],s.left/2,s.top/2,cw,ch,b.u);
        read(p[2],s.left/2,s.top/2,cw,ch,b.v);
        ai.upscale(b,fullRange,bt709,cancel);
        // Stable deterministic per-frame fidelity control; never claim
        // this is true multi-frame optical-flow stabilization.
        DetailMixer.apply(b,detailStrength);
        write(q[0],d.left,d.top,b.dw,b.dh,b.oy);
        write(q[1],d.left/2,d.top/2,(b.dw+1)/2,(b.dh+1)/2,b.ou);
        write(q[2],d.left/2,d.top/2,(b.dw+1)/2,(b.dh+1)/2,b.ov);
    }
    static void read(Image.Plane p,int left,int top,int w,int h,byte[] data) {
        ByteBuffer b=p.getBuffer();int row=p.getRowStride(),pixel=p.getPixelStride();
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int offset=(top+y)*row+(left+x)*pixel;
            if(offset<0 || offset>=b.limit()) throw new IllegalArgumentException("YUV 读取越界");
            data[y*w+x]=b.get(offset);
        }
    }
    static void write(Image.Plane p,int left,int top,int w,int h,byte[] data) {
        ByteBuffer b=p.getBuffer();int row=p.getRowStride(),pixel=p.getPixelStride();
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int offset=(top+y)*row+(left+x)*pixel;
            if(offset<0 || offset>=b.limit()) throw new IllegalArgumentException("YUV 写入越界");
            b.put(offset,data[y*w+x]);
        }
    }
    static void enhance(byte[] src,byte[] dst,int w,int h) {
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int i=y*w+x,c=src[i]&255;
            int avg=((src[y*w+Math.max(0,x-1)]&255)+(src[y*w+Math.min(w-1,x+1)]&255)
                    +(src[Math.max(0,y-1)*w+x]&255)+(src[Math.min(h-1,y+1)*w+x]&255)+2)/4;
            int diff=c-avg;
            int value=Math.round(Math.abs(diff)<7?c*.83f+avg*.17f:c+diff*.35f);
            dst[i]=(byte)clamp(value,0,255);
        }
    }
    static void resize(byte[] src,int sw,int sh,byte[] dst,int dw,int dh) {
        if(sw==dw&&sh==dh){System.arraycopy(src,0,dst,0,sw*sh);return;}
        for(int y=0;y<dh;y++) {
            double fy=Math.max(0,Math.min(sh-1,(y+.5)*sh/dh-.5));
            int y0=(int)fy,y1=Math.min(y0+1,sh-1);double ty=fy-y0;
            for(int x=0;x<dw;x++) {
                double fx=Math.max(0,Math.min(sw-1,(x+.5)*sw/dw-.5));
                int x0=(int)fx,x1=Math.min(x0+1,sw-1);double tx=fx-x0;
                double a=(src[y0*sw+x0]&255)*(1-tx)+(src[y0*sw+x1]&255)*tx;
                double b=(src[y1*sw+x0]&255)*(1-tx)+(src[y1*sw+x1]&255)*tx;
                dst[y*dw+x]=(byte)clamp((int)Math.round(a*(1-ty)+b*ty),0,255);
            }
        }
    }
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
}
