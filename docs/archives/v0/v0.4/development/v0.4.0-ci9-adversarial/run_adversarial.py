"""Isolated synthetic differential execution; writes only this new report directory."""
from pathlib import Path
import hashlib, json, os, re, shutil, subprocess

root = Path.cwd()
out = root / 'reports/release-v040/ci9-adversarial'
guard = Path.home()/'.nexus-hub/src/catalog/skills/workflow/user-edit-preservation/scripts/edit_guard.py'
env = dict(os.environ, NEXUS_EDIT_GUARD_SESSION='ci9-independent-adversarial')
def run(command, timeout=120):
    return subprocess.run(command, capture_output=True, timeout=timeout, creationflags=subprocess.CREATE_NO_WINDOW, env=env)
def record(path):
    p=run(['python',str(guard),'record',str(path),'--from','write'])
    assert p.returncode==0, p.stderr.decode(errors='replace')
def new(path, content):
    assert not path.exists(), str(path)
    path.write_text(content,encoding='utf-8'); record(path)
def sha(data): return hashlib.sha256(data).hexdigest()
for name in ('AdversarialParity.kt','run_adversarial.py'): record(out/name)
source_path=root/'app/src/main/java/com/bendourthe/milestone/domain/GlobalSearch.kt'
source=source_path.read_text(encoding='utf-8')
original=run(['git','show','d3df074:app/src/main/java/com/bendourthe/milestone/domain/GlobalSearch.kt']).stdout.decode('utf-8')
assert original.startswith('package ')
before=original.split('object GlobalSearch {')[0]
assert before==source.split('object GlobalSearch {')[0], 'Shared declarations changed'
# Only the object is renamed; data classes intentionally share current declarations.
reference='package com.bendourthe.milestone.domain\nimport java.util.Locale\n'+original[original.index('object GlobalSearch {'):].replace('object GlobalSearch {','object ReferenceGlobalSearch {',1)
new(out/'ReferenceGlobalSearch.kt',reference)
benefits=(root/'app/src/main/java/com/bendourthe/milestone/domain/Benefits.kt').read_text(encoding='utf-8')
types='package com.bendourthe.milestone.domain\n\n'+'\n\n'.join(re.search(p,benefits,re.MULTILINE|re.DOTALL).group() for p in (r'data class CardKey\(.*?^\}',r'enum class BenefitsCategory\(.*?^\}'))
new(out/'IsolatedDomainTypes.kt',types+'\n')
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,name,version):
    paths=list((cache/group/name/version).glob('*/*.jar')); assert len(paths)==1; return paths[0]
kotlin=[jar('org.jetbrains.kotlin',n,'2.4.10') for n in ('kotlin-compiler-embeddable','kotlin-stdlib','kotlin-script-runtime','kotlin-reflect','kotlin-daemon-embeddable')]
annotations=jar('org.jetbrains','annotations','13.0')
compiler=kotlin+[jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.11.0'),jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),annotations]
runtime=[kotlin[1],annotations]
java=str(Path(os.environ['JAVA_HOME'])/'bin/java.exe') if os.environ.get('JAVA_HOME') else shutil.which('java')
receipts=[]
for label,src in [('current',source_path),('mutation',out/'MutantGlobalSearch.kt')]:
    if label=='mutation':
        # Remove first-original-row preservation only in the prepared route. This must be caught.
        needle='if (index < (firstInput[programId]?.first ?: Int.MAX_VALUE)) firstInput[programId] = index to entry.programName'
        assert source.count(needle)==1
        new(src,source.replace(needle,'if (programId !in firstInput) firstInput[programId] = index to entry.programName'))
    classes=out/('classes-'+label); assert not classes.exists()
    cmd=[java,'-cp',os.pathsep.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',os.pathsep.join(map(str,runtime)),'-d',str(classes),str(src),str(out/'ReferenceGlobalSearch.kt'),str(out/'IsolatedDomainTypes.kt'),str(out/'AdversarialParity.kt')]
    compiled=run(cmd)
    new(out/(label+'-compile.log'),(compiled.stdout+compiled.stderr).decode(errors='replace'))
    assert compiled.returncode==0, (compiled.stdout+compiled.stderr).decode(errors='replace')[-2000:]
    executed=run([java,'-cp',os.pathsep.join(map(str,[classes]+runtime)),'com.bendourthe.milestone.domain.AdversarialParity'])
    output=(executed.stdout+executed.stderr).decode(errors='replace')
    new(out/(label+'-execution.log'),output)
    receipt={'variant':label,'compile_exit':compiled.returncode,'execution_exit':executed.returncode}
    if label=='current':
        assert executed.returncode==0, output[-1500:]
        receipt.update(json.loads(output.strip()))
    else:
        assert executed.returncode!=0 and 'structured' in output, 'Verifier did not kill injected grouping fault'
        receipt['mutation_detected']=True
        receipt['failure_case']=re.search(r'case=([^ ]+)',output).group(1)
    receipts.append(receipt); print(json.dumps(receipt))
assert source_path.read_text(encoding='utf-8')==source, 'Source changed during execution'
new(out/'execution-receipt.json',json.dumps({'scope':'Synthetic standalone JVM domain only; no Gradle, device, phone, private capture, or network','baseline':'d3df074','shared_declarations_exact_equal':True,'reference_object_only_renamed':True,'normalization_and_contextSources_unchanged':True,'checks':receipts,'inputs':{'current_raw_sha256':sha(source_path.read_bytes()),'reference_original_git_sha256':sha(original.encode()),'reference_renamed_sha256':sha(reference.encode()),'types_sha256':sha(types.encode())},'artifacts_sha256':{p.name:sha(p.read_bytes()) for p in out.iterdir() if p.is_file()}},indent=2)+'\n')
