"""Prepare pinned Real-ESRGAN ONNX asset and verify actual inference, offline at runtime."""
from pathlib import Path
from hashlib import sha256
import json
import numpy as np
from PIL import Image, ImageDraw
from huggingface_hub import hf_hub_download
import onnxruntime as ort

MODEL="realesr-general-x4v3.onnx"
SHA256="1940a93ee08283a0a7286183186357b1688fe9fa8ede74604b424586aaddf112"
REPO="CoderViking/realesr-general-x4v3-onnx"
source=Path(hf_hub_download(repo_id=REPO, filename=MODEL))
data=source.read_bytes()
digest=sha256(data).hexdigest()
assert digest==SHA256, f"MODEL HASH MISMATCH: {digest}"
assets=Path("app/src/main/assets")
assets.mkdir(parents=True,exist_ok=True)
(assets/MODEL).write_bytes(data)
session=ort.InferenceSession(str(source),providers=["CPUExecutionProvider"])
assert len(session.get_inputs())==1 and len(session.get_outputs())==1
inp=session.get_inputs()[0]
out=session.get_outputs()[0]
assert inp.name=="input" and len(inp.shape)==4
assert len(out.shape)==4

# Synthetic fixture intended to detect no-op model wiring, NOT measure perceived quality.
full=Image.new("RGB",(256,256),(75,110,140))
draw=ImageDraw.Draw(full)
for i in range(0,256,9):
    draw.line([(i,0),(256-i,255)],fill=((i*13)%256,230,(i*7)%256),width=2)
for i in range(15,240,23):
    draw.ellipse((i,30,i+27,57),outline="white",width=3)
draw.text((15,170),"SUPER RES",fill="white")
low=full.resize((64,64),Image.Resampling.BICUBIC)
arr=np.asarray(low,dtype=np.float32)/255.0
x=arr.transpose((2,0,1))[None,:,:,:]
y=session.run(None,{inp.name:x})[0]
assert y.shape==(1,3,256,256),f"Unexpected output shape {y.shape}"
assert np.isfinite(y).all()
sr=np.uint8(np.clip(y[0].transpose(1,2,0)*255,0,255))
sr_img=Image.fromarray(sr,"RGB").resize((128,128),Image.Resampling.LANCZOS)
bili=low.resize((128,128),Image.Resampling.BICUBIC)
ref=full.resize((128,128),Image.Resampling.LANCZOS)
mae=float(np.mean(np.abs(np.asarray(sr_img,dtype=np.float32)-np.asarray(bili,dtype=np.float32))))
assert mae>0.25,f"Model indistinguishable from bicubic interpolation ({mae:.4f})"
target=np.asarray(ref,dtype=np.float32)
rmse_bilinear=float(np.sqrt(np.mean((np.asarray(bili,dtype=np.float32)-target)**2)))
rmse_model=float(np.sqrt(np.mean((np.asarray(sr_img,dtype=np.float32)-target)**2)))
preview=Image.new("RGB",(3*128,128))
for i,img in enumerate([ref,bili,sr_img]):preview.paste(img,(i*128,0))
preview.save("model-synthetic-visual-check.png")
report={
  "source":f"https://huggingface.co/{REPO}",
  "model":MODEL,
  "sha256":digest,
  "size_bytes":len(data),
  "input":str(inp.shape),
  "output":str(out.shape),
  "actual_inference_output_shape":list(y.shape),
  "output_finite":True,
  "synthetic_test_difference_from_bicubic_mae":round(mae,3),
  "synthetic_test_rmse_bicubic":round(rmse_bilinear,3),
  "synthetic_test_rmse_real_esrgan":round(rmse_model,3),
  "synthetic_data_not_real_video_quality_proof":True,
}
(assets/"real-esrgan-model-info.json").write_text(json.dumps(report,indent=2)+"\n")
(assets/"Real-ESRGAN-THIRD-PARTY-NOTICE.txt").write_text(
  "Real-ESRGAN / SRVGGNetCompact pretrained model by Xintao Wang and contributors\n"
  "BSD-3-Clause license. https://github.com/xinntao/Real-ESRGAN/blob/master/LICENSE\n"
  "ONNX export distributed by CoderViking on Hugging Face; model SHA256 pinned.\n"
  "Runtime is ONNX Runtime for Android, distributed under MIT license.\n"
  "Frame-by-frame RGB enhancement; AI-generated details are not guaranteed accurate.\n")
print(json.dumps(report,indent=2))
