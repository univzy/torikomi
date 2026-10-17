// All filesystem, process and dialog access remains in the Rust application.
window.torikomi = {
  call: (command, ...args) => window.__TAURI__.core.invoke('dispatch', { command, args }),
  onState: callback => window.__TAURI__.event.listen('state', event => callback(event.payload)),
  onProgress: callback => window.__TAURI__.event.listen('progress', event => callback(event.payload)),
  onSetupProgress: callback => window.__TAURI__.event.listen('setup-progress', event => callback(event.payload)),
};
