// Runs in a Web Worker: browsers throttle timers in hidden tabs (Chrome to once a minute after
// 5 minutes), but not in workers. The tab is hidden for a long time while the user reviews the
// mapping file in Excel and connects the VPN, and the app must not shut down meanwhile.
function ping() {
    fetch('/api/ping', { method: 'POST' })
        .then(() => postMessage('ok'))
        .catch(() => postMessage('down'));
}
ping();
setInterval(ping, 5000);
