import { speech } from './bridge.js';
const status = document.getElementById('speech-status');
const bar = document.getElementById('speech-bar');
const stop = document.getElementById('speech-stop');
let run = 0;
stop.addEventListener('click', async () => {
  try { await speech.stop(); }
  catch (error) { status.textContent = error.message; }
});
for (const button of document.querySelectorAll('[data-speech-language]')) {
  button.disabled = !speech.available;
  button.addEventListener('click', async () => {
    const mine = ++run;
    status.textContent = 'Speaking…';
    bar.hidden = false;
    try {
      const result = await speech.speak({text: button.dataset.speechText, language: button.dataset.speechLanguage});
      if (mine === run) status.textContent = result.status === 'completed' ? 'Finished.' : 'Stopped.';
    } catch (error) { if (mine === run) status.textContent = error.message; }
    finally { if (mine === run) bar.hidden = true; }
  });
}
status.textContent = speech.available ? 'Choose a language below to hear pronunciation.' : 'Open in App² to hear native speech.';
