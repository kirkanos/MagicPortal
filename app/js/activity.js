function fmtTs(iso) {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso || '';
  return d.toLocaleString(LANG === 'en' ? 'en-GB' : 'de-DE');
}

function sourceLabel(src) {
  const map = {
    Sync: t('Sync', 'Sync'),
    Import: t('Import', 'Import'),
    Upload: t('Upload', 'Upload'),
    Backup: t('Backup', 'Backup'),
    Restore: t('Wiederherstellung', 'Restore'),
    Reset: t('Leeren', 'Reset'),
    Aktivität: t('Protokoll', 'Log'),
  };
  return map[src] || src;
}

async function loadActivity() {
  const box = document.getElementById('activity-list');
  let res;
  try {
    res = await fetch(API + '/activity', { cache: 'no-store', headers: uploadHeaders() });
  } catch (e) {
    box.innerHTML = `<p class="chart-note" style="color:#e05656">${t('Fehler beim Laden', 'Error loading')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  if (res.status === 403) {
    box.innerHTML = `<p class="chart-note">${t('Bitte zuerst oben rechts über „🔑 Login" anmelden, um das Protokoll zu sehen.', 'Please log in via “🔑 Login” (top right) to view the log.')}</p>`;
    return;
  }
  if (!res.ok) {
    box.innerHTML = `<p class="chart-note" style="color:#e05656">HTTP ${res.status}</p>`;
    return;
  }
  const items = await res.json();
  if (!items.length) {
    box.innerHTML = `<p class="chart-note">${t('Noch keine Einträge.', 'No entries yet.')}</p>`;
    return;
  }
  const rows = items
    .map((a) => {
      const isErr = a.level === 'error';
      return `<tr class="${isErr ? 'act-row-error' : ''}">
        <td class="act-ts" title="${escapeHTML(a.ts)}">${escapeHTML(fmtTs(a.ts))}</td>
        <td><span class="act-badge act-${isErr ? 'error' : 'info'}">${escapeHTML(sourceLabel(a.source))}</span></td>
        <td class="${isErr ? 'act-msg-error' : ''}">${isErr ? '⚠ ' : ''}${escapeHTML(a.message)}</td>
      </tr>`;
    })
    .join('');
  box.innerHTML = `<div class="stat-table"><table class="lang-table act-table">
    <thead><tr>
      <th>${t('Zeitpunkt', 'Time')}</th>
      <th>${t('Bereich', 'Area')}</th>
      <th>${t('Meldung', 'Message')}</th>
    </tr></thead>
    <tbody>${rows}</tbody>
  </table></div>`;
}

async function clearActivity() {
  if (!confirm(t('Protokoll wirklich leeren? Die bisherigen Einträge werden gelöscht.', 'Really clear the log? All current entries will be removed.'))) return;
  try {
    const res = await fetch(API + '/activity/clear', { method: 'POST', headers: uploadHeaders() });
    if (res.status === 403) {
      alert(t('Falsches oder fehlendes Passwort', 'Wrong or missing password'));
      return;
    }
    if (!res.ok) {
      alert(t('Leeren fehlgeschlagen.', 'Clearing failed.'));
      return;
    }
    loadActivity();
  } catch (e) {
    alert(t('Leeren fehlgeschlagen: ', 'Clearing failed: ') + e.message);
  }
}

document.getElementById('act-refresh').addEventListener('click', loadActivity);
document.getElementById('act-clear').addEventListener('click', clearActivity);
loadActivity();
