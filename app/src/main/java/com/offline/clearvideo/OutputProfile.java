package com.offline.clearvideo;

/** Size policy independent of Android, so it can be unit tested. */
final class OutputProfile {
    static final int NATIVE = 0, HD = 1, DOUBLE = 2, AI = 3, QUALITY_AI = 4;
    static int[] size(int w, int h, int mode) {
        if (w < 16 || h < 16 || Math.max(w,h) > 3840 || Math.min(w,h) > 2160)
            throw new IllegalArgumentException("仅支持 16×16 至 4K 的输入视频");
        if (mode < NATIVE || mode > QUALITY_AI) throw new IllegalArgumentException("未知处理模式");
        if (mode == AI || mode == QUALITY_AI) {
            if (Math.max(w,h) > 1280 || Math.min(w,h) > 720)
                throw new IllegalArgumentException("AI 模式暂限 720P 及以下视频；原视频过大，请选择普通增强");
            return new int[]{w*2,h*2};
        }
        double scale = mode == NATIVE ? 1 : Math.max(1, Math.min(2,
            Math.min((mode == HD ? 1920.0 : 3840.0)/Math.max(w,h),
                     (mode == HD ? 1080.0 : 2160.0)/Math.min(w,h))));
        return new int[]{Math.max(16, (int)Math.round(w*scale/2)*2),
                         Math.max(16, (int)Math.round(h*scale/2)*2)};
    }
    static int bitrate(int w,int h,int fps) {
        return (int)Math.max(1_400_000L,Math.min(30_000_000L,(long)w*h*fps/10));
    }
}
