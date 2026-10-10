// A fixed-prompt example. Native capabilities enter through the bridge wrapper only.
export const AI_SAMPLE_PROMPT = 'Write one short sentence explaining what a subapp is.';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Dependency injection keeps the example testable without a native host or live inference.
export function createAiDemoSession({ ai, load, save, changed = () => {}, pause = sleep }) {
  const state = { available: ai.available, connected: false, models: [], loading: false,
    pending: null, latest: null, retry: false, message: '', storageError: false };
  let polling = false;
  let completion = null;
  let cancelling = false;
  let saving = false;
  const emit = () => changed(state);
  const persist = () => save({ pending: state.pending, latest: state.latest });
  try {
    const saved = load();
    state.pending = saved.pending || null;
    state.latest = saved.latest || null;
  } catch {
    state.storageError = true;
    state.message = 'Saved AI demo data could not be read. Check data/ai-demo.json in the editor.';
  }

  async function saveCompletion() {
    if (!state.pending || saving) return;
    saving = true;
    const pending = state.pending;
    try {
      if (completion) state.latest = { ...pending, text: completion.text };
      // Keep the request ID on disk until acknowledgement succeeds.
      persist();
      try { await ai.acknowledge(pending.id); }
      catch (error) { if (error.code !== 'not_found') throw error; }
      state.pending = null;
      try { persist(); } catch (error) { state.pending = pending; throw error; }
      completion = null;
      state.retry = false;
      state.message = 'Response saved to data/ai-demo.json.';
    } catch {
      state.retry = true;
      state.message = 'Could not finish saving the response. Retry saving; no new AI request is needed.';
    }
    saving = false;
    emit();
  }

  async function resume() {
    if (!state.pending || polling || state.retry || cancelling) return;
    if (state.latest?.id === state.pending.id) { await saveCompletion(); return; }
    polling = true;
    const id = state.pending.id;
    state.message = 'Generating example… You can switch apps while this request runs.';
    emit();
    try {
      while (state.pending?.id === id && !cancelling) {
        const result = await ai.result(id);
        if (state.pending?.id !== id || cancelling) return;
        if (!result.done) { await pause(400); continue; }
        if (result.error) throw Object.assign(new Error(result.error.message), { code: result.error.code });
        completion = { text: result.value };
        await saveCompletion();
        return;
      }
    } catch (error) {
      if (state.pending?.id !== id || cancelling) return;
      // Terminal failures still occupy the native request slot until acknowledged.
      try { await ai.acknowledge(id); } catch (ackError) {
        if (ackError.code !== 'not_found') {
          state.message = `${error.message} Reopen the demo to retry releasing the request.`;
          emit();
          return;
        }
      }
      state.pending = null;
      try { persist(); } catch { state.storageError = true; }
      state.message = error.code === 'not_found'
        ? 'The previous request was interrupted or closed. Generate again when you are ready.'
        : `AI request failed: ${error.message}`;
      emit();
    } finally { polling = false; }
  }

  async function refresh() {
    if (state.loading) return;
    if (!state.available) {
      if (!state.storageError) state.message = 'AI is unavailable in browser previews. Open this demo in App².';
      emit(); return;
    }
    state.loading = true;
    emit();
    try {
      const { providers } = await ai.providers();
      state.connected = providers.some((p) => p.id === 'chatgpt' && p.connected);
      state.models = state.connected ? (await ai.models('chatgpt')).models : [];
      if (!state.pending && !state.storageError) state.message = state.connected
        ? (ai.developmentMock ? 'Ready. Development mock is offline and uses no ChatGPT plan usage.' : 'Ready. Generating an example uses your ChatGPT plan usage.')
        : 'Connect ChatGPT in the App² library → Settings → AI, then return here.';
    } catch (error) {
      state.connected = false;
      state.models = [];
      if (!state.storageError) state.message = `Could not load AI models: ${error.message}`;
    } finally { state.loading = false; emit(); }
    await resume();
  }

  async function generate(modelId, effort) {
    if (state.pending || state.loading || !state.connected || state.storageError) return;
    const model = state.models.find((m) => m.id === modelId);
    if (!model || (effort && !model.reasoningLevels.includes(effort))) return;
    state.loading = true;
    state.message = 'Starting example…'; emit();
    try {
      const request = { providerId: 'chatgpt', model: model.id,
        instructions: 'Keep the answer concise and use plain language.', input: AI_SAMPLE_PROMPT };
      if (effort) request.reasoningEffort = effort;
      const id = await ai.start(request);
      state.pending = { id, providerId: 'chatgpt', model: model.id, modelName: model.name,
        reasoningEffort: effort || null, prompt: AI_SAMPLE_PROMPT, createdAt: new Date().toISOString() };
      try { persist(); } catch {
        await ai.cancel(id).catch(() => {});
        state.pending = null;
        throw new Error('Could not save the request ID. The request was cancelled.');
      }
    } catch (error) { state.message = error.message; }
    finally { state.loading = false; emit(); }
    await resume();
  }

  async function cancel() {
    if (!state.pending || state.retry || cancelling) return;
    cancelling = true;
    const id = state.pending.id;
    try {
      await ai.cancel(id);
      state.pending = null;
      persist();
      state.message = 'Generation cancelled.';
    } catch (error) {
      if (error.code === 'not_found') {
        state.pending = null;
        try { persist(); } catch { state.storageError = true; }
        state.message = 'The request has already closed.';
      } else state.message = `Could not cancel: ${error.message}`;
    } finally { cancelling = false; emit(); }
  }

  emit();
  return { state, refresh, generate, cancel, resume, retrySaving: saveCompletion };
}

export function initAiDemo(bridge, store) {
  const $ = (id) => document.getElementById(id);
  const modelSelect = $('ai-model');
  const effortSelect = $('ai-reasoning');
  if (bridge.ai.developmentMock) {
    $('ai-description').textContent = 'Test a fixed sample question with the offline development mock.';
    $('ai-usage').textContent = 'Development mock: fixed responses, no credentials and no ChatGPT plan usage.';
  }
  let catalog = '';
  function renderReasoning() {
    const model = session.state.models.find((m) => m.id === modelSelect.value);
    effortSelect.replaceChildren(new Option('Provider default', ''));
    for (const effort of model?.reasoningLevels || []) effortSelect.add(new Option(effort, effort));
    $('ai-reasoning-field').hidden = !model?.reasoningLevels?.length;
  }
  let session;
  session = createAiDemoSession({ ai: bridge.ai, load: store.loadAiDemo, save: store.saveAiDemo,
    changed: (state) => {
      $('ai-status').textContent = state.message;
      const nextCatalog = JSON.stringify(state.models);
      if (nextCatalog !== catalog) {
        const selected = modelSelect.value;
        const effort = effortSelect.value;
        modelSelect.replaceChildren(...(state.models.length
          ? state.models.map((m) => new Option(m.name, m.id))
          : [new Option(state.available ? 'Connect ChatGPT to see models' : 'Unavailable in browser preview', '')]));
        if (state.models.some((m) => m.id === selected)) modelSelect.value = selected;
        catalog = nextCatalog;
        // Initialization emits before the session variable has been assigned.
        const model = state.models.find((m) => m.id === modelSelect.value);
        effortSelect.replaceChildren(new Option('Provider default', ''),
          ...(model?.reasoningLevels || []).map((level) => new Option(level, level)));
        if (model?.reasoningLevels?.includes(effort)) effortSelect.value = effort;
        $('ai-reasoning-field').hidden = !model?.reasoningLevels?.length;
      }
      const busy = !!state.pending || state.loading;
      modelSelect.disabled = busy || !state.connected;
      effortSelect.disabled = busy;
      $('ai-generate').disabled = busy || !state.connected || !state.models.length || state.storageError;
      $('ai-cancel').hidden = !state.pending || state.retry;
      $('ai-retry-save').hidden = !state.retry;
      $('ai-response-box').hidden = !state.latest;
      $('ai-response').textContent = state.latest?.text || '';
      $('ai-response-meta').textContent = state.latest
        ? `${state.latest.modelName} · Reasoning: ${state.latest.reasoningEffort || 'provider default'}` : '';
    } });
  modelSelect.onchange = renderReasoning;
  $('ai-generate').onclick = () => session.generate(modelSelect.value, effortSelect.value);
  $('ai-cancel').onclick = () => session.cancel();
  $('ai-retry-save').onclick = () => session.retrySaving();
  addEventListener('app2:ai:connection', () => session.refresh());
  addEventListener('app2:ai:status', (event) => {
    if (event.detail.id === session.state.pending?.id) session.resume();
  });
  addEventListener('pageshow', () => session.refresh());
  document.addEventListener('visibilitychange', () => { if (!document.hidden) session.refresh(); });
  session.refresh();
  return session;
}
