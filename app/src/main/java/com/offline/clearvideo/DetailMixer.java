package com.offline.clearvideo;

/**
 * Fidelity control for the pretrained RGB neural output.
 *
 * Natural uses a conservative blend with bicubic input. Balanced preserves
 * the model output, and Strong modestly boosts model residuals (which may
 * also amplify invented texture). This is NOT a skin detector or a restoration
 * of measured ground-truth pores or fabric fibres.
 */
final class DetailMixer {
    static final int NATURAL=0;
    static final int BALANCED=1;
    static final int STRONG=2;

    static void validate(int setting){
        if(setting<NATURAL||setting>STRONG)
            throw new IllegalArgumentException("未知 AI 细节强度");
    }
    static float gain(int setting){
        validate(setting);
        return setting==NATURAL?0.56f:setting==BALANCED?1f:1.18f;
    }
    static int blendValue(int baseline,int neural,float gain,int maxResidual){
        int diff=neural-baseline;
        if(diff>maxResidual)diff=maxResidual;
        if(diff< -maxResidual)diff= -maxResidual;
        int result=Math.round(baseline+gain*diff);
        return Math.min(255,Math.max(0,result));
    }
    static void mix(byte[] base,byte[] neural,int count,float factor,int maxResidual){
        if(base==null||neural==null||count<0||base.length<count||neural.length<count)
            throw new IllegalArgumentException("细节混合缓冲区不匹配");
        for(int i=0;i<count;i++){
            neural[i]=(byte)blendValue(base[i]&255,neural[i]&255,factor,maxResidual);
        }
    }
    static void apply(YuvEnhancer.Scratch s,int strength) {
        validate(strength);
        if(s.dw!=s.sw*2||s.dh!=s.sh*2)
            throw new IllegalArgumentException("仅支持 2 倍 AI 后处理");
        // The baseline must use precisely the same geometry as the neural output.
        byte[] baseY=new byte[s.oy.length];
        byte[] baseU=new byte[s.ou.length];
        byte[] baseV=new byte[s.ov.length];
        YuvEnhancer.resize(s.y,s.sw,s.sh,baseY,s.dw,s.dh);
        int icw=(s.sw+1)/2,ich=(s.sh+1)/2;
        int ocw=(s.dw+1)/2,och=(s.dh+1)/2;
        YuvEnhancer.resize(s.u,icw,ich,baseU,ocw,och);
        YuvEnhancer.resize(s.v,icw,ich,baseV,ocw,och);

        if(strength==BALANCED)return; // preserves v0.6 full-strength model output
        float yFactor=gain(strength);
        float colorFactor=strength==NATURAL?0.68f:1.03f;
        int yLimit=strength==NATURAL?60:92;
        int colorLimit=strength==NATURAL?32:46;
        mix(baseY,s.oy,s.oy.length,yFactor,yLimit);
        mix(baseU,s.ou,s.ou.length,colorFactor,colorLimit);
        mix(baseV,s.ov,s.ov.length,colorFactor,colorLimit);
    }
}
