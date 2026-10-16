#!/usr/bin/env python3
"""Ship pinned DexKit (including submodules) source alongside the combined APK."""
import pathlib,subprocess,tempfile,zipfile,json,hashlib,argparse
p=argparse.ArgumentParser();p.add_argument('--output',default='dist');args=p.parse_args()
revision='ffa6c51c38fe3ecfddb18d8949c30c48dbfbfd6a'
out=pathlib.Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='d2-dexkit-source-') as temp:
 root=pathlib.Path(temp)/'DexKit'
 subprocess.run(['git','clone','--depth','1','--branch','2.2.0','--recurse-submodules','https://github.com/LuckyPray/DexKit.git',str(root)],check=True)
 actual=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
 if actual!=revision:raise RuntimeError('DexKit tag revision changed')
 subprocess.run(['git','submodule','update','--init','--recursive'],cwd=root,check=True)
 archive=out/'DexKit-2.2.0-corresponding-source.zip'
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
  for path in sorted(root.rglob('*')):
   if '.git' in path.relative_to(root).parts or path.name=='.git':continue
   if path.is_file():z.write(path,'DexKit-2.2.0/'+path.relative_to(root).as_posix())
  z.writestr('DexKit-2.2.0/SOURCE-REVISION.txt',revision+'\n')
 (out/'DexKit-SOURCE-MANIFEST.json').write_text(json.dumps({'revision':revision,'version':'2.2.0','archive':archive.name,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'source_url':'https://github.com/LuckyPray/DexKit/tree/'+revision},indent=2)+'\n')
print('Packaged DexKit 2.2.0 source and recursive submodule sources')
