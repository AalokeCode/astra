class PcmCaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.pending = []
    this.sourceChunkSize = Math.max(128, Math.round(sampleRate * 0.04))
    this.targetChunkSize = 640
  }

  process(inputs) {
    const input = inputs[0]?.[0]
    if (!input) return true
    for (let i = 0; i < input.length; i += 1) this.pending.push(input[i])
    while (this.pending.length >= this.sourceChunkSize) {
      const source = this.pending.splice(0, this.sourceChunkSize)
      const pcm = new Int16Array(this.targetChunkSize)
      const ratio = source.length / this.targetChunkSize
      for (let i = 0; i < pcm.length; i += 1) {
        const position = i * ratio
        const left = Math.floor(position)
        const right = Math.min(left + 1, source.length - 1)
        const fraction = position - left
        const value = source[left] + (source[right] - source[left]) * fraction
        pcm[i] = Math.max(-32768, Math.min(32767, value * 32767))
      }
      this.port.postMessage(pcm.buffer, [pcm.buffer])
    }
    return true
  }
}

registerProcessor('pcm-capture-processor', PcmCaptureProcessor)
