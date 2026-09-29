class AudioWorklet extends AudioWorkletProcessor {
  constructor() {
    super();
    this.bufferSize = 4096; // 256ms at 16kHz
    this.buffer = new Int16Array(this.bufferSize);
    this.bufferIndex = 0;
    
    // Voice Activity Detection (VAD) properties
    // Lowered threshold to 0.0005 to catch quiet speech
    this.threshold = 0.0005; 
    this.framesSinceLastLoud = 100000; // Start assumed silent
    // Set tail to 1.5 seconds as requested for ultra-fast VAD response
    this.tailFrames = 16000 * 1.5; 
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0];
    if (input && input.length > 0) {
      const channelData = input[0];
      
      // We no longer use a local VAD or gain multiplier to avoid clipping 
      // and stuttering. Gemini's server handles VAD natively.
      for (let i = 0; i < channelData.length; i++) {
        let s = channelData[i];
        s = Math.max(-1, Math.min(1, s));
        this.buffer[this.bufferIndex++] = s < 0 ? s * 0x8000 : s * 0x7FFF;

        if (this.bufferIndex >= this.bufferSize) {
          this.port.postMessage(new Int16Array(this.buffer));
          this.bufferIndex = 0;
        }
      }
    }
    return true;
  }
}

registerProcessor("audio-worklet", AudioWorklet);
