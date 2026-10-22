"""Reproducible offline synthetic skin/fabric video-detail sanity checks.

These are stylised patterns, not photographs of people and not proof that AI
restores authentic pores or cloth fibres. Compare the same 2x display size
against a bicubic-only baseline; report gains AND losses without cherry-picking.
"""
from pathlib import Path
import json
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import onnxruntime as ort

model=Path("app/src/main/assets/realesr-general-x4v3.onnx")
assert model.is_file(),"Model missing; run prepare_real_esrgan.py first"
session=ort.InferenceSession(str(model),providers=["CPUExecutionProvider"])
name=session.get_inputs()[0].name
rng=np.random.default_rng(712)
H=W=256
yy,xx=np.mgrid[0:H,0:W]

skin=np.empty((H,W,3),dtype=np.float32)
illum=9*np.sin(xx/64)+5*np.cos(yy/52)
noise=rng.normal(0,0.8,(H,W))
for c,(base,amp) in enumerate([(190,1.0),(146,0.85),(121,0.7)]):
    skin[:,:,c]=base+amp*illum+noise
# sparse pores, faint spots and a few fine hairs
for k in range(1000):
    x=int(rng.integers(2,W-2));y=int(rng.integers(2,H-2))
    shade=float(rng.uniform(2,13))
    skin[y,x,:]-=shade
for k in range(9):
    x=int(rng.integers(6,W-6));y=int(rng.integers(8,H-24))
    length=int(rng.integers(6,18))
    for i in range(length):
        dx=int(i*.35)
        if y+i<H and x+dx<W: skin[y+i,x+dx,:]-=14

# woven fabric threads: diagonally lit warp/weft with soft folds
warp=7*np.cos(xx*2*np.pi/6)
weft=6*np.cos(yy*2*np.pi/7)
fold=21*np.sin((xx+yy*.25)*2*np.pi/126)
check=3*((xx//16+yy//18)%2)
fabric=np.empty((H,W,3),dtype=np.float32)
for c,(base,scale) in enumerate([(77,1),(97,.95),(117,1.15)]):
    fabric[:,:,c]=base+scale*(warp+weft+fold+check)+rng.normal(0,1.1,(H,W))

cases={"skin_like_synthetic":skin,"woven_fabric_synthetic":fabric}
results={}
previews=[]
font=ImageFont.load_default()
for title,raw in cases.items():
    truth=Image.fromarray(np.uint8(np.clip(raw,0,255)),"RGB")
    low=truth.resize((64,64),Image.Resampling.BICUBIC)
    inp=(np.asarray(low,dtype=np.float32)/255.0).transpose(2,0,1)[None]
    neural=session.run(None,{name:inp})[0]
    assert neural.shape==(1,3,256,256),neural.shape
    assert np.isfinite(neural).all()
    ai=Image.fromarray(np.uint8(np.clip(neural[0].transpose(1,2,0)*255,0,255)),"RGB")
    ai=ai.resize((128,128),Image.Resampling.LANCZOS)
    bicubic=low.resize((128,128),Image.Resampling.BICUBIC)
    ref=truth.resize((128,128),Image.Resampling.LANCZOS)
    t=np.asarray(ref,dtype=np.float32)
    def mse(im):
        z=np.asarray(im,dtype=np.float32)
        return float(np.mean((z-t)**2))
    def gradient(im):
        a=np.asarray(im,dtype=np.float32)
        return float(np.mean(np.abs(np.diff(a,axis=1)))+np.mean(np.abs(np.diff(a,axis=0))))
    b=mse(bicubic);a=mse(ai)
    results[title]={
        "mse_bicubic":round(b,4),
        "mse_neural":round(a,4),
        "neural_better_mse":a<b,
        "mean_abs_change_from_bicubic":round(float(np.mean(np.abs(np.asarray(ai,dtype=np.float32)-np.asarray(bicubic,dtype=np.float32)))),4),
        "gradient_reference":round(gradient(ref),4),
        "gradient_bicubic":round(gradient(bicubic),4),
        "gradient_neural":round(gradient(ai),4)
    }
    strip=Image.new("RGB",(3*128,128+22),("white"))
    for i,(label,image) in enumerate([("synthetic reference",ref),("bicubic only",bicubic),("Real-ESRGAN 2x",ai)]):
        strip.paste(image,(i*128,22))
        ImageDraw.Draw(strip).text((i*128+4,5),label,fill="black",font=font)
    previews.append(strip)
out=Image.new("RGB",(384,(128+22)*len(previews)),"white")
for i,strip in enumerate(previews):out.paste(strip,(0,i*150))
out.save("model-skin-cloth-synthetic-comparison.png")
report={
    "purpose":"sanity-only; synthetic patterns; no video temporal metric; no claim of authentic skin recovery",
    "all_test_cases":results,
    "test_resolution":"64x64 input; 128x128 evaluation output; 256x256 pretrained neural prediction",
}
Path("model-skin-cloth-synthetic-report.json").write_text(
    json.dumps(report,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
print(json.dumps(report,indent=2,ensure_ascii=False))
