(() => {
  'use strict';
  let installPrompt = null;
  const standalone = window.matchMedia('(display-mode: standalone)');
  const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const notify = () => window.dispatchEvent(new CustomEvent('asyl-install-ready'));
  window.AsylInstall = Object.freeze({
    get isStandalone() { return standalone.matches || navigator.standalone === true; },
    get isIOS() { return isIOS; },
    get canPrompt() { return installPrompt !== null; },
    async promptInstall() {
      if (!installPrompt) return 'unavailable';
      const prompt = installPrompt;
      installPrompt = null;
      notify();
      try {
        await prompt.prompt();
        return (await prompt.userChoice).outcome;
      } catch { return 'unavailable'; }
    }
  });
  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    installPrompt = event;
    notify();
  });
  window.addEventListener('appinstalled', () => { installPrompt = null; notify(); });
  standalone.addEventListener('change', notify);
  if ('serviceWorker' in navigator && window.isSecureContext) {
    const register = () => navigator.serviceWorker.register('/sw.js', {scope: '/', updateViaCache: 'none'}).catch(() => {
      // Installation enhancement must never prevent normal online use.
    });
    if (document.readyState === 'complete') register();
    else window.addEventListener('load', register, {once: true});
  }
  notify();
})();
