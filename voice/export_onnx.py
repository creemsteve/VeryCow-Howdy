"""Export the pinned 16 kHz DAC model for shared Java/Android inference."""
from pathlib import Path
import json, torch, dac

torch.set_num_threads(4)
out = Path("build/models"); out.mkdir(parents=True, exist_ok=True)
model = dac.DAC.load(dac.utils.download(model_type="16khz", tag="0.0.5")).eval()
for module in model.modules():
    try:
        torch.nn.utils.remove_weight_norm(module)
    except ValueError:
        pass

class Encoder(torch.nn.Module):
    def __init__(self):
        super().__init__(); self.codec = model
    def forward(self, pcm):
        residual = self.codec.encoder(pcm)
        codes = []
        for quantizer in self.codec.quantizer.quantizers:
            z_e = quantizer.in_proj(residual)
            z_q, indices = quantizer.decode_latents(z_e)
            residual = residual - quantizer.out_proj(z_q)
            codes.append(indices)
        return torch.stack(codes, dim=1)

class Decoder(torch.nn.Module):
    def __init__(self):
        super().__init__(); self.codec = model
    def forward(self, codes, mask):
        z = 0.0
        for i, quantizer in enumerate(self.codec.quantizer.quantizers):
            z = z + quantizer.out_proj(quantizer.decode_code(codes[:, i, :])) * mask[i]
        return self.codec.decode(z)

pcm = torch.zeros(1, 1, 8320)
with torch.inference_mode():
    codes = Encoder()(pcm)
    torch.onnx.export(Encoder(), (pcm,), str(out / "dac-encoder.onnx"), input_names=["pcm"], output_names=["codes"], dynamic_axes={"pcm": {2: "samples"}, "codes": {2: "frames"}}, opset_version=17, dynamo=False)
    torch.onnx.export(Decoder(), (codes, torch.ones(model.n_codebooks)), str(out / "dac-decoder.onnx"), input_names=["codes", "mask"], output_names=["pcm"], dynamic_axes={"codes": {2: "frames"}, "pcm": {2: "samples"}}, opset_version=17, dynamo=False)
(out / "dac.json").write_text(json.dumps({"sampleRate": model.sample_rate, "hop": int(model.hop_length), "codebooks": model.n_codebooks, "modelTag": "0.0.5"}), encoding="utf-8")
print("Exported", codes.shape, "to", out.resolve())
