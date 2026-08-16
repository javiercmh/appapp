package com.example.runtimecompiler.templates

object DefaultWebApp {

    val DEFAULT_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>Persistent Runtime Web App</title>
  <style>
    :root {
      --bg-gradient: linear-gradient(135deg, #0f172a 0%, #1e1b4b 50%, #172554 100%);
      --card-bg: rgba(30, 41, 59, 0.75);
      --card-border: rgba(255, 255, 255, 0.12);
      --primary: #3b82f6;
      --primary-hover: #2563eb;
      --secondary: #64748b;
      --secondary-hover: #475569;
      --danger: #ef4444;
      --accent: #10b981;
      --text-main: #f8fafc;
      --text-muted: #94a3b8;
      --input-bg: rgba(15, 23, 42, 0.65);
      --input-border: rgba(148, 163, 184, 0.25);
      --input-focus: #3b82f6;
    }

    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      -webkit-tap-highlight-color: transparent;
    }

    body {
      background: var(--bg-gradient);
      color: var(--text-main);
      min-height: 100vh;
      padding: 16px;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: flex-start;
    }

    .container {
      width: 100%;
      max-width: 520px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .header-card {
      background: var(--card-bg);
      backdrop-filter: blur(14px);
      -webkit-backdrop-filter: blur(14px);
      border: 1px solid var(--card-border);
      border-radius: 16px;
      padding: 20px;
      text-align: center;
      box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.3);
    }

    .header-title {
      font-size: 1.35rem;
      font-weight: 700;
      letter-spacing: -0.02em;
      margin-bottom: 6px;
      background: linear-gradient(90deg, #60a5fa, #34d399);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }

    .header-subtitle {
      font-size: 0.85rem;
      color: var(--text-muted);
    }

    .storage-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      margin-top: 10px;
      padding: 4px 12px;
      border-radius: 9999px;
      font-size: 0.75rem;
      font-family: monospace;
      background: rgba(16, 185, 129, 0.15);
      border: 1px solid rgba(16, 185, 129, 0.3);
      color: #34d399;
    }

    .storage-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: #34d399;
      box-shadow: 0 0 8px #34d399;
      animation: pulse 2s infinite;
    }

    @keyframes pulse {
      0%, 100% { opacity: 1; transform: scale(1); }
      50% { opacity: 0.5; transform: scale(0.85); }
    }

    .input-card {
      background: var(--card-bg);
      backdrop-filter: blur(14px);
      -webkit-backdrop-filter: blur(14px);
      border: 1px solid var(--card-border);
      border-radius: 16px;
      padding: 18px;
      display: flex;
      flex-direction: column;
      gap: 14px;
      box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.3);
    }

    .text-input {
      width: 100%;
      background: var(--input-bg);
      border: 1.5px solid var(--input-border);
      border-radius: 12px;
      padding: 14px 16px;
      font-size: 1rem;
      color: var(--text-main);
      outline: none;
      transition: all 0.2s ease;
    }

    .text-input:focus {
      border-color: var(--input-focus);
      box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.25);
    }

    .text-input::placeholder {
      color: var(--text-muted);
    }

    .button-group {
      display: flex;
      gap: 10px;
    }

    .btn {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      padding: 13px 18px;
      font-size: 0.95rem;
      font-weight: 600;
      border-radius: 12px;
      border: none;
      cursor: pointer;
      transition: all 0.18s ease;
      user-select: none;
    }

    .btn:active {
      transform: scale(0.97);
    }

    .btn-primary {
      background: var(--primary);
      color: #ffffff;
      box-shadow: 0 4px 14px rgba(59, 130, 246, 0.35);
    }

    .btn-primary:hover {
      background: var(--primary-hover);
    }

    .btn-undo {
      background: var(--secondary);
      color: #ffffff;
      box-shadow: 0 4px 14px rgba(100, 116, 139, 0.25);
    }

    .btn-undo:hover {
      background: var(--secondary-hover);
    }

    .btn-undo:disabled {
      opacity: 0.4;
      cursor: not-allowed;
      transform: none;
      box-shadow: none;
    }

    .list-card {
      background: var(--card-bg);
      backdrop-filter: blur(14px);
      -webkit-backdrop-filter: blur(14px);
      border: 1px solid var(--card-border);
      border-radius: 16px;
      padding: 18px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-height: 180px;
      box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.3);
    }

    .list-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--card-border);
      padding-bottom: 10px;
    }

    .list-title {
      font-size: 0.95rem;
      font-weight: 600;
      color: var(--text-main);
    }

    .list-count {
      font-size: 0.75rem;
      color: var(--text-muted);
      background: rgba(255, 255, 255, 0.08);
      padding: 2px 8px;
      border-radius: 999px;
    }

    .items-container {
      display: flex;
      flex-direction: column;
      gap: 8px;
      overflow-y: auto;
      max-height: 280px;
    }

    .item-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      background: rgba(15, 23, 42, 0.55);
      border: 1px solid var(--card-border);
      border-radius: 10px;
      padding: 10px 14px;
      animation: fadeIn 0.2s ease-out;
    }

    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(-6px); }
      to { opacity: 1; transform: translateY(0); }
    }

    .item-content {
      display: flex;
      align-items: center;
      gap: 10px;
      overflow: hidden;
    }

    .item-index {
      font-size: 0.75rem;
      font-weight: 700;
      color: #60a5fa;
      background: rgba(59, 130, 246, 0.15);
      width: 22px;
      height: 22px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }

    .item-text {
      font-size: 0.9rem;
      color: var(--text-main);
      word-break: break-word;
    }

    .item-time {
      font-size: 0.7rem;
      color: var(--text-muted);
      flex-shrink: 0;
      font-family: monospace;
      margin-left: 8px;
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      height: 140px;
      color: var(--text-muted);
      font-size: 0.88rem;
      text-align: center;
      gap: 8px;
    }

    .empty-icon {
      font-size: 1.8rem;
      opacity: 0.4;
    }

    .telemetry-card {
      background: rgba(15, 23, 42, 0.85);
      border: 1px solid var(--card-border);
      border-radius: 12px;
      padding: 12px 16px;
      font-family: monospace;
      font-size: 0.75rem;
      color: var(--text-muted);
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .telemetry-row {
      display: flex;
      justify-content: space-between;
    }

    .telemetry-val {
      color: #60a5fa;
    }
  </style>
</head>
<body>

  <div class="container">
    <!-- Header -->
    <div class="header-card">
      <h1 class="header-title">Persistent Runtime Web App</h1>
      <p class="header-subtitle">Text Input, Submit &amp; Undo with Persistent Storage Memory</p>
      <div class="storage-badge">
        <span class="storage-dot"></span>
        <span id="storage-status">Persistent Storage: Active</span>
      </div>
    </div>

    <!-- Input Box & Action Buttons -->
    <div class="input-card">
      <input 
        type="text" 
        id="text-input" 
        class="text-input" 
        placeholder="Enter any text to add to saved list..." 
        autocomplete="off"
        autofocus
      />
      <div class="button-group">
        <button id="btn-submit" class="btn btn-primary">
          <span>Submit</span>
        </button>
        <button id="btn-undo" class="btn btn-undo" disabled>
          <span>Undo</span>
        </button>
      </div>
    </div>

    <!-- Items List -->
    <div class="list-card">
      <div class="list-header">
        <span class="list-title">Saved Items (Restored on Relaunch)</span>
        <span id="item-count" class="list-count">0 items</span>
      </div>

      <div id="items-container" class="items-container">
        <div id="empty-state" class="empty-state">
          <div class="empty-icon">📝</div>
          <div>No items saved yet. Type text above and tap Submit!</div>
        </div>
      </div>
    </div>

    <!-- Live Storage & Memory Telemetry -->
    <div class="telemetry-card">
      <div class="telemetry-row">
        <span>Persistent File:</span>
        <span id="stat-file-name" class="telemetry-val">saved_items.json</span>
      </div>
      <div class="telemetry-row">
        <span>Storage Backend:</span>
        <span id="stat-backend" class="telemetry-val">Android Internal Files</span>
      </div>
      <div class="telemetry-row">
        <span>Disk Space Usable:</span>
        <span id="stat-disk-space" class="telemetry-val">Checking...</span>
      </div>
      <div class="telemetry-row">
        <span>Last Disk Sync:</span>
        <span id="stat-last-sync" class="telemetry-val">Initialized</span>
      </div>
    </div>
  </div>

  <script>
    // --- State Persistence & Storage Management ---
    const STORAGE_FILE = "saved_items.json";
    let items = [];

    const inputField = document.getElementById('text-input');
    const submitBtn = document.getElementById('btn-submit');
    const undoBtn = document.getElementById('btn-undo');
    const itemsContainer = document.getElementById('items-container');
    const emptyState = document.getElementById('empty-state');
    const itemCountSpan = document.getElementById('item-count');
    const storageStatusSpan = document.getElementById('storage-status');
    const statFileName = document.getElementById('stat-file-name');
    const statBackend = document.getElementById('stat-backend');
    const statDiskSpace = document.getElementById('stat-disk-space');
    const statLastSync = document.getElementById('stat-last-sync');

    // Load persistent state from Android file storage on launch
    function loadPersistentState() {
      try {
        let rawData = "";
        
        // 1. Try Native Android Storage Bridge
        if (window.AndroidStorage) {
          statBackend.textContent = "Android Native Storage (filesDir)";
          rawData = window.AndroidStorage.readFile(STORAGE_FILE);
          if (!rawData) {
            // Fallback to key-value state
            rawData = window.AndroidStorage.loadState("saved_items_state", "");
          }
        } 
        // 2. Fallback to Browser localStorage
        else if (window.localStorage) {
          statBackend.textContent = "Browser LocalStorage (Fallback)";
          rawData = localStorage.getItem(STORAGE_FILE) || "";
        }

        if (rawData && rawData.trim().length > 0) {
          items = JSON.parse(rawData);
          console.log("[Storage] Restored " + items.length + " items from " + STORAGE_FILE);
          storageStatusSpan.textContent = "Restored " + items.length + " items from disk";
        } else {
          console.log("[Storage] No existing data in " + STORAGE_FILE + ". Starting fresh.");
          storageStatusSpan.textContent = "Persistent Storage: Ready";
        }
      } catch (err) {
        console.error("[Storage] Error loading saved state:", err);
        items = [];
      }

      updateStorageTelemetry();
      renderList();
    }

    // Save current items list to persistent file storage
    function savePersistentState() {
      const jsonStr = JSON.stringify(items);
      try {
        if (window.AndroidStorage) {
          window.AndroidStorage.writeFile(STORAGE_FILE, jsonStr);
          window.AndroidStorage.saveState("saved_items_state", jsonStr);
          console.log("[Storage] Saved " + items.length + " items to " + STORAGE_FILE + " (" + jsonStr.length + " bytes)");
        } else if (window.localStorage) {
          localStorage.setItem(STORAGE_FILE, jsonStr);
        }
      } catch (err) {
        console.error("[Storage] Failed to save state to disk:", err);
      }

      const now = new Date().toLocaleTimeString();
      statLastSync.textContent = now + " (" + jsonStr.length + " bytes on disk)";
      updateStorageTelemetry();
    }

    // Update storage disk space telemetry
    function updateStorageTelemetry() {
      if (window.AndroidStorage) {
        try {
          const statsJson = window.AndroidStorage.getStorageStats();
          const stats = JSON.parse(statsJson);
          const usableMB = Math.round(stats.usableSpaceBytes / (1024 * 1024));
          statDiskSpace.textContent = usableMB + " MB Free";
        } catch (e) {
          console.error("[Storage] Error reading storage stats:", e);
        }
      } else {
        statDiskSpace.textContent = "Simulated Web Storage";
      }
    }

    // Render list items in DOM
    function renderList() {
      itemsContainer.innerHTML = '';

      if (items.length === 0) {
        itemsContainer.appendChild(emptyState);
        emptyState.style.display = 'flex';
        undoBtn.disabled = true;
      } else {
        emptyState.style.display = 'none';
        undoBtn.disabled = false;

        items.forEach((item, index) => {
          const row = document.createElement('div');
          row.className = 'item-row';

          const content = document.createElement('div');
          content.className = 'item-content';

          const badge = document.createElement('span');
          badge.className = 'item-index';
          badge.textContent = index + 1;

          const textSpan = document.createElement('span');
          textSpan.className = 'item-text';
          textSpan.textContent = item.text;

          content.appendChild(badge);
          content.appendChild(textSpan);

          const timeSpan = document.createElement('span');
          timeSpan.className = 'item-time';
          timeSpan.textContent = item.timestamp;

          row.appendChild(content);
          row.appendChild(timeSpan);
          itemsContainer.appendChild(row);
        });

        // Auto-scroll to latest item
        itemsContainer.scrollTop = itemsContainer.scrollHeight;
      }

      itemCountSpan.textContent = items.length + (items.length === 1 ? " item" : " items");
    }

    // Add item (Submit)
    function addItem() {
      const text = inputField.value.trim();
      if (!text) {
        inputField.focus();
        return;
      }

      const newItem = {
        id: Date.now(),
        text: text,
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      };

      items.push(newItem);
      inputField.value = '';
      inputField.focus();

      renderList();
      savePersistentState();
    }

    // Remove last item (Undo)
    function undoItem() {
      if (items.length > 0) {
        const removed = items.pop();
        console.log("[Storage] Undo removed item:", removed.text);
        renderList();
        savePersistentState();
      }
    }

    // Event listeners
    submitBtn.addEventListener('click', addItem);
    undoBtn.addEventListener('click', undoItem);

    inputField.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        addItem();
      }
    });

    // Initialize on page ready
    window.addEventListener('DOMContentLoaded', () => {
      loadPersistentState();
    });
  </script>
</body>
</html>
""".trimIndent()
}
