import { speech } from './bridge.js';
const status = document.getElementById('speech-status');
for (const button of document.querySelectorAll('[data-speech-language]')) {
  button.disabled = !speech.available;
  button.addEventListener('click', async () => {
    status.textContent = 'Speaking…';
    try {
      const result = await speech.speak({text: button.dataset.speechText, language: button.dataset.speechLanguage});
      status.textContent = result.status === 'completed' ? 'Finished.' : 'Stopped.';
    } catch (error) { status.textContent = error.message; }
  });
}
status.textContent = speech.available ? 'Choose a language below to hear pronunciation.' : 'Open in App² to hear native speech.';
