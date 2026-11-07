"""Persistent memory-only DAC codec. JSON lines over inherited stdio, never a network port."""
import sys, json, base64, struct, os, contextlib, time

model = None

def load():
    global model
    if model is not None:
        return model
    # Library logging must never corrupt the stdio protocol.
    with contextlib.redirect_stdout(sys.stderr):
        import torch, dac
        torch.set_num_threads(max(1, min(4, (os.cpu_count() or 2) // 2)))
        path = os.environ.get("VCH_DAC_MODEL") or dac.utils.download(model_type="16khz")
        model = dac.DAC.load(path).eval().to("cpu")
    return model

def pack10(values):
    result = bytearray(); acc = bits = 0
    for v in values:
        acc = (acc << 10) | int(v); bits += 10
        while bits >= 8:
            bits -= 8; result.append((acc >> bits) & 255)
    if bits:
        result.append((acc << (8 - bits)) & 255)
    return bytes(result)

def unpack10(data, count):
    values = []; acc = bits = 0
    for v in data:
        acc = (acc << 8) | v; bits += 8
        while bits >= 10 and len(values) < count:
            bits -= 10; values.append((acc >> bits) & 1023)
    if len(values) != count:
        raise ValueError("Truncated codec packet")
    return values

def handle(request):
    import torch, numpy as np
    m = load(); op = request.get("op", "init")
    with torch.inference_mode():
        if op == "init":
            return {"ready": True, "sampleRate": int(m.sample_rate), "hop": int(m.hop_length)}
        data = base64.b64decode(request["data"], validate=True)
        if op == "encode":
            if len(data) > 64000 or len(data) < 4 or len(data) % 4:
                raise ValueError("Invalid PCM block")
            pcm = np.frombuffer(data, dtype="<f4").copy()
            if not np.isfinite(pcm).all():
                raise ValueError("Invalid PCM samples")
            x = torch.from_numpy(np.clip(pcm, -1, 1)).reshape(1, 1, -1)
            rate = max(2000, min(24000, int(request.get("bitRate", 8000))))
            n = max(1, min(m.n_codebooks, round(rate * int(m.hop_length) / (10 * int(m.sample_rate)))))
            start = time.perf_counter()
            x = torch.nn.functional.pad(x, (0, int(m.hop_length)))
            _, codes, _, _, _ = m.encode(m.preprocess(x, m.sample_rate), n_quantizers=n)
            shape = codes.shape
            packet = struct.pack("<4sHHI", b"VCD1", shape[1], shape[2], len(pcm)) + pack10(codes.cpu().flatten().tolist())
            return {"data": base64.b64encode(packet).decode(), "ms": round((time.perf_counter()-start)*1000, 1)}
        if op == "decode":
            if len(data) < 12 or len(data) > 32000:
                raise ValueError("Invalid DAC packet")
            magic, n, frames, samples = struct.unpack("<4sHHI", data[:12])
            if magic != b"VCD1" or not 1 <= n <= m.n_codebooks or not 1 <= frames <= 256 or not 1 <= samples <= 16000 or len(data) != 12 + (n * frames * 10 + 7) // 8:
                raise ValueError("Invalid DAC dimensions")
            codes = torch.tensor(unpack10(data[12:], n*frames), dtype=torch.long).reshape(1, n, frames)
            z, _, _ = m.quantizer.from_codes(codes)
            audio = m.decode(z)[..., :samples].cpu().numpy().astype("<f4").tobytes()
            return {"data": base64.b64encode(audio).decode(), "sampleRate": int(m.sample_rate)}
        raise ValueError("Unsupported codec operation")

if __name__ == "__main__":
    for line in sys.stdin:
        try:
            if len(line) > 100000:
                raise ValueError("Request too large")
            result = handle(json.loads(line))
        except Exception as error:
            result = {"error": str(error)}
        print(json.dumps(result, separators=(",", ":")), flush=True)
