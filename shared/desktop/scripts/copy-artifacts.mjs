import { copyFile, mkdir, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const platform = process.argv[2];
if (!['windows', 'macos'].includes(platform)) throw Error('Expected windows or macos');
const root = fileURLToPath(new URL('../../../', import.meta.url));
const target = platform === 'windows' ? 'release/bundle/nsis' : 'release/bundle/dmg';
const source = path.join(root, 'shared/desktop/src-tauri/target', target);
const extension = platform === 'windows' ? '.exe' : '.dmg';
const artifacts = (await readdir(source)).filter(name => name.endsWith(extension));
if (!artifacts.length) throw Error(`No ${extension} installers in ${source}`);
const output = path.join(root, platform, 'dist');
await mkdir(output, { recursive:true });
for (const artifact of artifacts) {
  await copyFile(path.join(source,artifact),path.join(output,artifact));
  console.log(path.join(output,artifact));
}
