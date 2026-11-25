#!/usr/bin/env python3
"""Fast tests of actual stage orchestration, with fake compilers; no SDK/JVM builds."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / 'tools/build-stage.py'


class BuildStages(unittest.TestCase):
    def test_artifact_cache_invalidates_and_timeout_is_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output, record = [root / name for name in ('input', 'output', 'record')]
            source.write_text('first')
            command = [sys.executable, str(RUNNER), 'copy', '--record', str(record), '--input', str(source), '--output', str(output)]
            copy = [sys.executable, '-c', f'import shutil;shutil.copyfile({str(source)!r},{str(output)!r})']
            def run(*options, body=copy):
                return subprocess.run([*command, *options, '--', *body], capture_output=True, text=True, timeout=4)
            self.assertEqual(run('--plan').returncode, 3)
            self.assertFalse(output.exists())
            self.assertEqual(run().returncode, 0)
            self.assertIn(': hit', run().stdout)
            output.write_text('corrupt')
            self.assertIn(': MISS', run().stdout)
            self.assertEqual(output.read_text(), 'first')
            source.write_text('second')
            self.assertIn(': MISS', run().stdout)
            self.assertEqual(output.read_text(), 'second')
            self.assertEqual(run('--seconds', '.1', body=[sys.executable, '-c', 'import time;time.sleep(20)']).returncode, 124)
            self.assertFalse(record.exists())
            self.assertEqual(run().returncode, 0)
            source.unlink()
            self.assertEqual(run('--plan').returncode, 3)

    def test_actual_test_apk_script_resumes_and_tracks_packaging_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('tools', 'tests/tools', 'tests/android', 'demos', 'bin', 'jdk', 'kotlin/bin', 'kotlin/lib', 'sdk/build-tools/36.0.0/lib', 'sdk/platforms/android-37.0', 'build/compose', 'build/embedding-consumer/assets', 'build/runtime-dex/host-stable', 'build/embedding-dex', 'build/toolchain'):
                (root/name).mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT/'tests/tools/build-cutover-test.sh', root/'tests/tools/build-cutover-test.sh')
            for name in ('build-stage.py', 'build-cache.py'):
                shutil.copy(ROOT/'tools'/name, root/'tools'/name)
            (root/'demos/build.sh').write_text('printf \'%s\\n\' \'{"sources":["tests/android/Test.kt"],"app_apis":[],"runtime_cp":["runtime.jar"]}\'')
            (root/'tools/package-java-resources.py').write_text('')
            for name in ('tests/android/Test.kt', 'jdk/release', 'runtime.jar', 'kotlin/lib/kotlin-compiler.jar', 'kotlin/lib/kotlin-stdlib.jar', 'kotlin/lib/compose-compiler-plugin.jar', 'sdk/platforms/android-37.0/android.jar', 'sdk/build-tools/36.0.0/lib/apksigner.jar', 'build/compose/res.zip', 'build/embedding-consumer/assets/policy.js', 'build/runtime-dex/host-stable/classes.dex', 'build/embedding-dex/classes.dex', 'build/toolchain/r8.jar', 'build/toolchain/desugar.json', 'build/debug.keystore', 'other.keystore'):
                (root/name).write_text(name)
            (root/'build/compose/resources.txt').write_text('build/compose/res.zip\n')
            (root/'build/compose/packages.txt').write_text('test.resources')
            fake = '''#!/usr/bin/env python3
import os,sys,pathlib,shutil,zipfile,time
root=pathlib.Path(os.environ['ROOT']);name=pathlib.Path(sys.argv[0]).name;a=sys.argv[1:]
def put(p,text='compiled'):
 p=pathlib.Path(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
if name=='aapt2':
 put(pathlib.Path(a[a.index('--java')+1])/'R.java')
 with zipfile.ZipFile(a[a.index('-o')+1],'w') as z:z.writestr('AndroidManifest.xml','manifest')
elif name in ('javac','kotlinc'):put(pathlib.Path(a[a.index('-d')+1])/(name+'.class'))
elif name=='jar':
 with zipfile.ZipFile(a[a.index('--file')+1],'w') as z:z.writestr('test.class','bytecode')
elif name=='java':
 if (root/'slow-dex').exists():time.sleep(20)
 put(pathlib.Path(a[a.index('--output')+1])/'classes.dex')
elif name=='zipalign':shutil.copyfile(a[-2],a[-1])
elif name=='apksigner':shutil.copyfile(a[-1],a[a.index('--out')+1])
elif name=='timeout':
 if (root/'slow-dex').exists() and a[-1]=='dex':a[2]='.1s'
 os.execv('/usr/bin/timeout',['timeout',*a])
'''
            for name in ('bin/javac','bin/java','bin/jar','bin/timeout','kotlin/bin/kotlinc','sdk/build-tools/36.0.0/aapt2','sdk/build-tools/36.0.0/zipalign','sdk/build-tools/36.0.0/apksigner'):
                (root/name).write_text(fake);(root/name).chmod(0o755)
            env={**os.environ,'ROOT':str(root),'PATH':str(root/'bin')+':'+os.environ['PATH'],'JAVA_HOME':str(root/'jdk'),'KOTLIN_HOME':str(root/'kotlin'),'ANDROID_SDK_ROOT':str(root/'sdk')}
            def run(**extra):
                return subprocess.run(['bash','tests/tools/build-cutover-test.sh'],cwd=root,env={**env,**extra},capture_output=True,text=True,timeout=10)
            result=run();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            result=run();self.assertEqual(result.returncode,0,result.stdout+result.stderr);self.assertNotIn(': MISS',result.stdout)
            (root/'build/embedding-consumer/assets/policy.js').write_text('new policy')
            result=run();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('resources: MISS',result.stdout);self.assertIn('test_kotlin: hit',result.stdout);self.assertIn('dex: hit',result.stdout)
            result=run(DEAL_TEST_KEYSTORE='other.keystore');self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('package_apk: MISS',result.stdout);self.assertIn('test_kotlin: hit',result.stdout)
            out=root/'build/v08-cutover/device-test'
            (out/'dex/classes.dex').write_text('corrupt');(root/'slow-dex').touch()
            result=run(DEAL_TEST_KEYSTORE='other.keystore');self.assertEqual(result.returncode,124,result.stdout+result.stderr)
            self.assertFalse((out/'stages/dex.json').exists());self.assertTrue((out/'stages/test_kotlin.json').exists())
            (root/'slow-dex').unlink()
            result=run(DEAL_TEST_KEYSTORE='other.keystore');self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('test_kotlin: hit',result.stdout);self.assertIn('dex: MISS',result.stdout)
            (out/'tests.apk').write_text('corrupt')
            result=run(DEAL_TEST_KEYSTORE='other.keystore');self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('package_apk: MISS',result.stdout);self.assertIn('dex: hit',result.stdout)
            (root/'tests/android/Test.kt').write_text('new fixture')
            result=run(DEAL_TEST_KEYSTORE='other.keystore');self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('test_kotlin: MISS',result.stdout)

    def test_composer_groups_keep_each_original_fixture_call_once(self):
        import re
        text=(ROOT/'tests/tools/test-composer.js').read_text().split('const group=')[0]
        calls=re.findall(r"\['(\w+)'(?:,'\w+')?\]",text)
        expected='rejects disclosureReplay construct booleanFigure clockPart clockInputPart clockNeedSource clockDurationUnbuilt clockRejects writtenFormula writtenFormulaCases writtenFormulaPart assemblyRejects effectCompletion definitionConflict admissionCases readerConnection planOperation descriptorDraft contextOperation bindingReasons customWholeTrigger triggerOperations assemblyCases entriesNeedPart entriesEmptyPart rowTapPart presentationCases foreignAction numericTextCase seedDefaults figureRejects entryAggregateCases'.split()
        self.assertCountEqual(calls,expected)
        self.assertEqual(len(calls),len(set(calls)))

    def test_seam_timeout_restores_bytes_and_never_counts_as_pass(self):
        from unittest.mock import patch
        spec=importlib.util.spec_from_file_location('seams',ROOT/'tests/tools/test-verification-seams.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); path=root/'policy.js';path.write_text('enabled')
            seam={'id':'timeout-control','path':'policy.js','command':['unused']}
            with patch.object(module,'ROOT',root), patch.object(module,'anchor',return_value=(b'enabled',b'broken')), patch.object(module,'track'), patch.object(module,'release') as release, patch.object(module,'bounded_run',return_value=subprocess.CompletedProcess([],124,'','')):
                with self.assertRaisesRegex(AssertionError,'infrastructure exit 124'):
                    module.observe(seam)
                self.assertEqual(path.read_text(),'enabled')
                release.assert_called_once()
            nested=subprocess.CompletedProcess([],1,'',"subprocess.CalledProcessError: returned non-zero exit status 124.")
            with patch.object(module,'ROOT',root), patch.object(module,'anchor',return_value=(b'enabled',b'broken')), patch.object(module,'track'), patch.object(module,'release'), patch.object(module,'bounded_run',return_value=nested):
                with self.assertRaisesRegex(AssertionError,'nested timeout'):
                    module.observe(seam)
                self.assertEqual(path.read_text(),'enabled')

    def test_boundary_preparation_bound_does_not_relax_proof(self):
        from unittest.mock import patch
        spec=importlib.util.spec_from_file_location('boundary_bounds',ROOT/'tools/boundary-proof.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            stage=Path(directory)
            with patch.object(module,'STAGE',stage), patch.object(module,'stage_tree',return_value=stage), patch.object(module,'runtime_classpath',return_value='runtime'), patch.object(module.library_inputs,'dependency_roots',return_value={'deal':ROOT,'deal-ui':ROOT}), patch.object(module.subprocess,'run') as run, patch.dict(os.environ,{'DEAL_BUILD_STAGE_SECONDS':'130'}):
                module.isolated_digests(Path('android'),Path('aidl'),prepare_stage='java_base')
                args,kwargs=run.call_args
                self.assertEqual(args[0][2],'240s')
                self.assertEqual(kwargs['env']['DEAL_BUILD_STAGE_SECONDS'],'130')
                with patch.dict(os.environ,{'DEAL_BUILD_STAGE_SECONDS':'300'}):
                    module.isolated_digests(Path('android'),Path('aidl'),prepare_stage='java_base')
                    self.assertEqual(run.call_args.args[0][2],'305s')
                with self.assertRaises(SystemExit):
                    module.isolated_digests(Path('android'),Path('aidl'))
                self.assertEqual(run.call_args.args[0][2],'600s','proof keeps upstream\'s independent deadline')
                with patch.dict(os.environ,{'DEAL_BUILD_STAGE_SECONDS':'0'}), self.assertRaises(SystemExit):
                    module.isolated_digests(Path('android'),Path('aidl'),prepare_stage='java_base')

    def test_boundary_output_corruption_is_not_a_cache_hit(self):
        spec=importlib.util.spec_from_file_location('boundary_test',ROOT/'tests/tools/test-standalone-boundary.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        test=module.StandaloneBoundaryTest('test_a_package_this_build_did_not_produce_is_never_recorded_as_proven')
        test.test_a_package_this_build_did_not_produce_is_never_recorded_as_proven()

    def test_actual_library_script_separates_stages_and_resumes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('tools', 'core/host', 'api', 'ui/assistance', 'android/src', 'android/aidl', 'android/assets/embedding', 'docs', 'dep/deal', 'dep/std', 'dep/skills/write-deal/references', 'ui-dep/ui', 'ui-dep/runtime-js', 'ui-dep/src/main/java/deal/ui', 'bin', 'kotlin/bin', 'kotlin/lib', 'jdk'):
                (root / name).mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / 'build.sh', root / 'build.sh')
            for name in ('build-stage.py', 'build-cache.py'):
                shutil.copy(ROOT / 'tools' / name, root / 'tools' / name)
            (root / 'tools/dependencies.py').write_text('')
            (root / 'tools/pack-conformance.py').write_text('import pathlib,sys\na=sys.argv;p=pathlib.Path(a[a.index("--assets")+1]);p.mkdir(parents=True,exist_ok=True);(p/"pack.json").write_text("projection");p=pathlib.Path(a[a.index("--kotlin")+1]);p.mkdir(parents=True,exist_ok=True);(p/"PackProjections.kt").write_text("projection")')
            (root / 'tools/theme-build.py').write_text('import pathlib,sys;pathlib.Path(sys.argv[2]).write_text("theme")')
            (root / 'tools/protocol-build.py').write_text('import pathlib,sys;p=pathlib.Path(sys.argv[2])/"aar/assets/protocol-application";p.mkdir(parents=True,exist_ok=True);(p/"main.js").write_text("protocol")')
            files = ['android/AndroidManifest.xml', 'android/src/Sample.kt', 'android/aidl/a.aidl', 'android/assets/embedding/a.js', 'core/host/a.d.deal', 'core/generation.deal', 'core/discovery.deal', 'core/assistance.deal', 'core/logging.deal', 'core/assistance-policy.deal', 'core/controller.deal', 'api/embedding.d.deal', 'api/graphics.d.deal', 'ui/theme.json', 'ui/prop-constraints.json', 'ui/platform.dealui-pack', 'ui/assistance/suggestion.dealui', 'docs/spec-v0.8.md', 'dep/deal/runtime.js', 'dep/std/a.js', 'dep/std/a.d.deal', 'dep/skills/write-deal/references/language.md', 'ui-dep/ui/a.deal', 'ui-dep/runtime-js/session.js', 'ui-dep/runtime-js/checkpoint.js', 'tools/ui-vocabulary.json', 'tools/vocabulary-inventory.py', 'tools/controller-mechanisms.d.deal', 'tools/application-consumer.deal', 'tools/protocol-consumer.deal', 'android.jar', 'runtime.jar', 'jdk/release', 'kotlin/lib/kotlin-compiler.jar', 'kotlin/lib/kotlin-stdlib.jar', 'kotlin/lib/compose-compiler-plugin.jar']
            files += ['tools/'+name+'.java' for name in ('CapabilityApi','AssistanceUi','GenerationGuidance','GenerationBindings','FragmentSyntax','FragmentRegions','FragmentQualifier','FragmentAnalysis','FragmentMechanisms','PayloadConsumption')]
            files += ['ui-dep/src/main/java/deal/ui/'+name+'.java' for name in ('UiModel','UiParser','UiDiagnostic','UiDiagnosticJson','UiRepairDiagnostics','UiCompilerWorkspace','CanonicalCompiler','UiChecker','UiBorrowedValueChecker','UiDealGenerator','UiSourceGenerator','DealUiDealSource','UiCheckpointCompiler')]
            for file in files:
                (root / file).write_text(file)
            fake = '''#!/usr/bin/env python3
import os,sys,pathlib,zipfile
name=pathlib.Path(sys.argv[0]).name;a=sys.argv[1:];root=pathlib.Path(os.environ['ROOT'])
with (root/'calls').open('a') as f:f.write(name+' '+' '.join(a)+'\\n')
def put(p,text='compiled'):
 p=pathlib.Path(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
if name in ('javac','kotlinc'):
 if name=='kotlinc' and (root/'fail-kotlin').exists():sys.exit(9)
 p=pathlib.Path(a[a.index('-d')+1]);put(p/(name+'.class'),name)
elif name=='java':
 if 'CapabilityApi' in a:put(a[-1])
 elif 'GenerationGuidance' in a:put(a[-1])
 elif 'AssistanceUi' in a:put(pathlib.Path(a[-2])/'src/experience_entry.deal')
 elif 'GenerationBindings' in a:put(a[-1])
 elif '--output' in a:put(pathlib.Path(a[a.index('--output')+1])/'main.js')
 elif 'deal.ui.UiCheckpointCompiler' in a:put(pathlib.Path(a[-1])/'main.js')
elif name=='jar':
 p=pathlib.Path(a[a.index('--file')+1]);p.parent.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(p,'w') as z:z.writestr('classes', 'compiled')
'''
            for tool in ('javac','java','jar','aidl'):
                (root/'bin'/tool).write_text(fake); (root/'bin'/tool).chmod(0o755)
            (root/'kotlin/bin/kotlinc').write_text(fake); (root/'kotlin/bin/kotlinc').chmod(0o755)
            env = {**os.environ, 'PATH':str(root/'bin')+':'+os.environ['PATH'], 'ROOT':str(root), 'DEAL_ROOT':str(root/'dep'), 'DEAL_UI_ROOT':str(root/'ui-dep'), 'JAVA_HOME':str(root/'jdk'), 'KOTLIN_HOME':str(root/'kotlin'), 'ANDROID_JAR':str(root/'android.jar'), 'RUNTIME_CP':str(root/'runtime.jar'), 'AIDL':str(root/'bin/aidl'), 'OUTPUT_DIR':str(root/'out')}
            def run(**extra):
                return subprocess.run(['bash', 'build.sh'], cwd=root, env={**env, **extra}, capture_output=True, text=True, timeout=8)
            result=run(); self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertTrue((root/'out/deal-embedding.aar').exists())
            self.assertTrue((root/'out/generated/CapabilityApi.kt').exists())
            result=run();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertNotIn(': MISS',result.stdout)
            (root/'core/generation.deal').write_text('changed policy')
            result=run();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('generation: MISS',result.stdout)
            self.assertIn('kotlin: hit',result.stdout)
            self.assertIn('java_base: hit',result.stdout)
            (root/'out/kotlin-classes/kotlinc.class').write_text('corrupt')
            (root/'fail-kotlin').touch()
            result=run();self.assertEqual(result.returncode,9,result.stdout+result.stderr)
            self.assertFalse((root/'out/stages/kotlin.json').exists())
            (root/'fail-kotlin').unlink()
            result=run(DEAL_BUILD_STAGE='kotlin');self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertNotIn('generation:',result.stdout)
            result=run();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('kotlin: hit',result.stdout)
            # Selected stages cannot consume a stale prerequisite.
            (root/'dep/deal/runtime.js').write_text('compiler changed')
            result=run(DEAL_BUILD_STAGE='generation');self.assertEqual(result.returncode,3)
            self.assertIn('Prepare prerequisite first: DEAL_BUILD_STAGE=java_base',result.stderr)


if __name__ == '__main__':
    unittest.main()
