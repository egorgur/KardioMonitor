import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

export function extractBand9Key(text) {
  const keys = new Set();
  for (const line of text.split('\n')) {
    if (!/Xiaomi Smart Band 9(?![0-9]|\s+(?:Active|Pro))/i.test(line)) continue;
    const models = new Set(line.match(/(?:Xiaomi |Mi )?(?:Smart )?Band \d+(?: (?:Active|Pro))?/gi));
    if (models.size !== 1) continue;
    for (const match of line.matchAll(/(?:encryptKey|token|authKey|huamiAuthKey)["\s:=]+([a-fA-F0-9]{32})(?![a-fA-F0-9])/g)) {
      keys.add(match[1].toLowerCase());
    }
  }
  if (keys.size !== 1) throw new Error(`Expected one model-associated key; found ${keys.size}.`);
  const [key] = keys;
  if (/^0+$/.test(key)) throw new Error('All-zero candidate is not accepted.');
  return key;
}

function main() {
  const [serial, mode = 'check'] = process.argv.slice(2);
  if (!serial || !['check', 'stage'].includes(mode)) {
    throw new Error('Usage: node tools/band9-prepare-key.mjs SERIAL [check|stage]');
  }
  const adb = (args, input) => execFileSync('adb', ['-s', serial, ...args], {
    encoding: 'utf8', maxBuffer: 24 * 1024 * 1024, timeout: 30_000,
    input, stdio: ['pipe', 'pipe', 'pipe'],
  });
  const key = extractBand9Key(adb([
    'shell', 'cat', '/sdcard/Android/data/com.xiaomi.wearable/files/log/XiaomiFit.main.log',
  ]));
  if (mode === 'stage') {
    adb(['shell', 'run-as', 'ru.kardiomonitor.band9probe', 'sh', '-c',
      "'umask 077; mkdir -p files && cat > files/auth-key && chmod 600 files/auth-key'"], key);
    console.log('Band 9 candidate staged in diagnostic app private storage; value not printed.');
  } else {
    console.log('One model-associated Band 9 key candidate found; authentication not yet proven.');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch {
    // Ошибка adb может содержать исходный вывод; наружу отдаётся только безопасное сообщение.
    console.error('Band 9 key preparation failed. No key or source log has been printed.');
    process.exitCode = 1;
  }
}
