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
sys.path.insert(0, str(ROOT / 'tests/tools'))
import lane_bounds  # the one declaration the composing harnesses and their lane bounds read

# One cap per stage kind this item drives, declared beside the lanes it bounds: `tests/check.py` derives the
# `infra` lane bound from the same table, and the count below is this harness's own, so a stage added here
# cannot outgrow its lane and be stopped by an outer kill that names nothing.
OBSERVED = {}


def capped(stage, command, **kwargs):
    """Drive one extracted block under its declared cap, counting it against the declared run count."""
    seconds, _declared = lane_bounds.INFRA_CAPS[stage]
    OBSERVED[stage] = OBSERVED.get(stage, 0) + 1
    return subprocess.run(command, timeout=seconds, **kwargs)


def tearDownModule():
    over = {stage: (count, lane_bounds.INFRA_CAPS[stage][1]) for stage, count in OBSERVED.items()
            if count > lane_bounds.INFRA_CAPS[stage][1]}
    if over:
        raise AssertionError(f'this item drives more stages than it declares (observed, declared): {over}')


class BuildStages(unittest.TestCase):
    def test_artifact_cache_invalidates_and_timeout_is_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output, record = [root / name for name in ('input', 'output', 'record')]
            source.write_text('first')
            command = [sys.executable, str(RUNNER), 'copy', '--record', str(record), '--input', str(source), '--output', str(output)]
            copy = [sys.executable, '-c', f'import shutil;shutil.copyfile({str(source)!r},{str(output)!r})']
            def run(*options, body=copy):
                return capped('stage-runner', [*command, *options, '--', *body], capture_output=True, text=True)
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
            env.pop('DEAL_TEST_COMPILE_SECONDS',None)  # the default bound is the property under test
            def run(**extra):
                return capped('cutover-apk', ['bash','tests/tools/build-cutover-test.sh'],cwd=root,env={**env,**extra},capture_output=True,text=True)
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

    def test_each_control_gets_a_bound_that_outlives_its_owning_test(self):
        spec=importlib.util.spec_from_file_location('seams',ROOT/'tests/tools/test-verification-seams.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        # A renderer control compiles its 26-source Kotlin set cold before any assertion runs: 19 s idle
        # and 33 s under this gate's load, so a control bound below that kills the control before its
        # owning test can report the broken rule and the family counts as unobserved.
        renderer=['bash','tests/tools/test-renderer-rules.sh']
        self.assertGreater(module.RUN_BOUND,36)
        # The resolved bound is the one the owning test's process tree actually receives.
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); path=root/'policy.kt';path.write_text('enabled')
            seam={'id':'bound-control','path':'policy.kt','command':renderer}
            with patch.object(module,'ROOT',root), patch.object(module,'anchor',return_value=(b'enabled',b'broken')), patch.object(module,'track'), patch.object(module,'release'), patch.object(module,'bounded_run',return_value=subprocess.CompletedProcess([],1,'the owning test failed','')) as bounded:
                module.observe(seam)
            self.assertEqual(bounded.call_args.kwargs['timeout'],module.RUN_BOUND)
            # `--seconds` still decides that bound, which is how the lane's own timeout control is
            # observed without waiting for the declared one.
            with patch.object(module,'ROOT',root), patch.object(module,'anchor',return_value=(b'enabled',b'broken')), patch.object(module,'track'), patch.object(module,'release'), patch.object(module,'bounded_run',return_value=subprocess.CompletedProcess([],1,'the owning test failed','')) as bounded:
                module.RUN_BOUND=5
                module.observe(seam)
            self.assertEqual(bounded.call_args.kwargs['timeout'],5)

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
        # The outer preparation bound is the stage's own declared bound plus the copy and audit, so the
        # expected values are derived from the one declaration instead of a second hard-coded number.
        declared=module.stage_bounds()['java_base']+30
        with tempfile.TemporaryDirectory() as directory:
            stage=Path(directory)
            with patch.object(module,'STAGE',stage), patch.object(module,'stage_tree',return_value=stage), patch.object(module,'runtime_classpath',return_value='runtime'), patch.object(module.library_inputs,'dependency_roots',return_value={'deal':ROOT,'deal-ui':ROOT}), patch.object(module.subprocess,'run') as run, patch.dict(os.environ,{}) as environ:
                environ.pop('DEAL_BUILD_STAGE_SECONDS',None)
                module.isolated_digests(Path('android'),Path('aidl'),prepare_stage='java_base')
                args,kwargs=run.call_args
                self.assertEqual(args[0][2],f'{declared}s','a preparation is bounded by the stage declaration it runs')
                # An explicitly requested inner bound widens this outer one with it, so the stage still stops
                # with its own name; `main()` refuses the override for the same reason instead of accepting a
                # bound the declaration does not contain.
                with patch.dict(os.environ,{'DEAL_BUILD_STAGE_SECONDS':'300'}):
                    module.isolated_digests(Path('android'),Path('aidl'),prepare_stage='java_base')
                    self.assertEqual(run.call_args.args[0][2],'305s','a requested inner bound widens the outer one with it')
                with patch.dict(os.environ,{'DEAL_BUILD_STAGE_SECONDS':'0'}), self.assertRaises(SystemExit):
                    module.isolated_digests(Path('android'),Path('aidl'),prepare_stage='java_base')
                self.assertEqual(run.call_args.args[0][2],'305s','a refused override never reaches a build')
                with self.assertRaises(SystemExit):
                    module.isolated_digests(Path('android'),Path('aidl'))
                self.assertEqual(run.call_args.args[0][2],f'{module.isolated_build_seconds()}s','a whole proof keeps its own independent deadline')

    def test_boundary_output_corruption_is_not_a_cache_hit(self):
        spec=importlib.util.spec_from_file_location('boundary_test',ROOT/'tests/tools/test-standalone-boundary.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        test=module.StandaloneBoundaryTest('test_a_package_this_build_did_not_produce_is_never_recorded_as_proven')
        test.test_a_package_this_build_did_not_produce_is_never_recorded_as_proven()

    def test_actual_library_script_separates_stages_and_resumes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('tools', 'core/host', 'api', 'ui/assistance', 'android/src', 'android/aidl', 'android/assets/embedding', 'docs', 'dep/deal', 'dep/docs', 'dep/std', 'dep/skills/write-deal/references', 'ui-dep/ui', 'ui-dep/runtime-js', 'ui-dep/src/main/java/deal/ui', 'bin', 'kotlin/bin', 'kotlin/lib', 'jdk'):
                (root / name).mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / 'build.sh', root / 'build.sh')
            for name in ('build-stage.py', 'build-cache.py', 'stage-bounds.txt'):
                shutil.copy(ROOT / 'tools' / name, root / 'tools' / name)
            (root / 'tools/dependencies.py').write_text('')
            (root / 'tools/pack-conformance.py').write_text('import pathlib,sys\na=sys.argv;p=pathlib.Path(a[a.index("--assets")+1]);p.mkdir(parents=True,exist_ok=True);(p/"pack.json").write_text("projection");p=pathlib.Path(a[a.index("--kotlin")+1]);p.mkdir(parents=True,exist_ok=True);(p/"PackProjections.kt").write_text("projection")')
            (root / 'tools/theme-build.py').write_text('import pathlib,sys;pathlib.Path(sys.argv[2]).write_text("theme")')
            (root / 'tools/protocol-build.py').write_text('import pathlib,sys;p=pathlib.Path(sys.argv[2])/"aar/assets/protocol-application";p.mkdir(parents=True,exist_ok=True);(p/"main.js").write_text("protocol")')
            files = ['android/AndroidManifest.xml', 'android/src/Sample.kt', 'android/aidl/a.aidl', 'android/assets/embedding/a.js', 'core/host/a.d.deal', 'core/generation.deal', 'core/discovery.deal', 'core/assistance.deal', 'core/logging.deal', 'core/assistance-policy.deal', 'core/controller.deal', 'api/embedding.d.deal', 'api/graphics.d.deal', 'api/day.d.deal', 'ui/theme.json', 'ui/prop-constraints.json', 'ui/platform.dealui-pack', 'ui/assistance/suggestion.dealui', 'docs/spec-v0.8.md', 'dep/deal/runtime.js', 'dep/docs/spec-v1.6.md', 'dep/std/a.js', 'dep/std/a.d.deal', 'dep/skills/write-deal/references/language.md', 'ui-dep/ui/a.deal', 'ui-dep/runtime-js/session.js', 'ui-dep/runtime-js/checkpoint.js', 'tools/ui-vocabulary.json', 'tools/vocabulary-inventory.py', 'tools/controller-mechanisms.d.deal', 'tools/application-consumer.deal', 'tools/protocol-consumer.deal', 'android.jar', 'runtime.jar', 'jdk/release', 'kotlin/lib/kotlin-compiler.jar', 'kotlin/lib/kotlin-stdlib.jar', 'kotlin/lib/compose-compiler-plugin.jar']
            files += ['tools/'+name+'.java' for name in ('CapabilityApi','AssistanceUi','GenerationGuidance','GenerationBindings','FragmentSyntax','FragmentRegions','FragmentQualifier','FragmentAnalysis','FragmentMechanisms','PayloadConsumption')]
            files += ['ui-dep/src/main/java/deal/ui/'+name+'.java' for name in ('UiModel','UiTypes','UiNominals','UiParser','UiDiagnostic','UiDiagnosticJson','UiRepairDiagnostics','UiCompilerWorkspace','CanonicalCompiler','UiChecker','UiFlowScope','UiBorrowedValueChecker','UiDealGenerator','UiViewBindings','UiSourceGenerator','DealUiDealSource','UiCheckpointCompiler')]
            for file in files:
                (root / file).write_text(file)
            fake = '''#!/usr/bin/env python3
import os,sys,pathlib,zipfile,time
name=pathlib.Path(sys.argv[0]).name;a=sys.argv[1:];root=pathlib.Path(os.environ['ROOT'])
with (root/'calls').open('a') as f:f.write(name+' '+' '.join(a)+'\\n')
def put(p,text='compiled'):
 p=pathlib.Path(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
if name in ('javac','kotlinc'):
 if name=='kotlinc' and (root/'fail-kotlin').exists():sys.exit(9)
 if name=='kotlinc' and (root/'slow-kotlin').exists():time.sleep(20)
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
            env.pop('DEAL_BUILD_STAGE_SECONDS',None)  # the default bound is the property under test
            def run(**extra):
                return capped('library-build', ['bash', 'build.sh'], cwd=root, env={**env, **extra}, capture_output=True, text=True)
            result=run(); self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertTrue((root/'out/deal-embedding.aar').exists())
            self.assertTrue((root/'out/generated/CapabilityApi.kt').exists())
            result=run();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertNotIn(': MISS',result.stdout)
            binding = root/'ui-dep/src/main/java/deal/ui/UiViewBindings.java'
            binding.write_text('changed action specialization')
            # Core type projection and module-identity normalization are compiler inputs too.
            type_sources = [root/f'ui-dep/src/main/java/deal/ui/{name}.java' for name in ('UiTypes', 'UiNominals')]
            for source in type_sources:
                source.write_text('changed '+source.stem)
            (root/'core/generation.deal').write_text('changed policy')
            result=run();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('generation: MISS',result.stdout)
            self.assertIn('kotlin: hit',result.stdout)
            self.assertIn('java_base: MISS',result.stdout)
            self.assertIn(str(binding), (root/'out/sources.txt').read_text())
            for source in type_sources:
                self.assertIn(str(source), (root/'out/sources.txt').read_text())
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
            (root/'dep/deal/runtime.js').write_text('deal')
            # Every stage prints and runs under its own declared preparation bound, never one shared
            # default: the ten stages declare different bounds because their coldest costs differ.
            result=run();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            for stage, seconds in declared_bounds().items():
                self.assertRegex(result.stdout,rf'\[build-stage\] {stage}: (hit|MISS) bound={seconds}s')
            # A stage the declaration does not cover is refused with its name instead of inheriting a
            # bound short enough to fail a cold compile.
            table=root/'tools/stage-bounds.txt'; original=table.read_text()
            table.write_text(''.join(line for line in original.splitlines(keepends=True) if not line.startswith('package_aar ')))
            result=run();self.assertEqual(result.returncode,2,result.stdout+result.stderr)
            self.assertIn('No declared preparation bound for build stage: package_aar',result.stderr)
            table.write_text(original)
            # An explicitly declared bound still wins over the stage's own, and a stage that runs past it
            # fails as a timeout instead of being accepted.
            (root/'slow-kotlin').touch(); (root/'out/kotlin-classes/kotlinc.class').write_text('corrupt')
            result=run(DEAL_BUILD_STAGE='kotlin',DEAL_BUILD_STAGE_SECONDS='1')
            self.assertEqual(result.returncode,124,result.stdout+result.stderr)
            self.assertIn('kotlin: MISS bound=1s',result.stdout)
            (root/'slow-kotlin').unlink()
            result=run(DEAL_BUILD_STAGE='kotlin');self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn(f'kotlin: MISS bound={declared_bounds()["kotlin"]}s',result.stdout)
            self.assertNotIn('No declared preparation bound',result.stderr)

    def test_declared_stage_bounds_cover_every_stage_and_their_containers(self):
        import re
        spec=importlib.util.spec_from_file_location('seam_controls',ROOT/'tests/tools/test-verification-seams.py')
        seams=importlib.util.module_from_spec(spec);spec.loader.exec_module(seams)
        table=declared_bounds()
        staged=re.findall(r'\bstage ([a-z_]+)$',(ROOT/'build.sh').read_text(),re.M)
        for names in re.findall(r'for name in ([a-z_ ]+); do',(ROOT/'build.sh').read_text()):
            staged+=names.split()
        self.assertCountEqual(list(table),staged,'build.sh stages a stage with no declared bound or declares an unused bound')
        allowance=re.search(r"case \$\{DEAL_BUILD_STAGE:-\} in\n\s+''\|([^)]*)\)",(ROOT/'build.sh').read_text())
        self.assertIsNotNone(allowance,'build.sh has no DEAL_BUILD_STAGE allowlist')
        self.assertCountEqual(list(table),allowance.group(1).split('|'))
        # One declaration feeds the lane bounds as well, so a lane can never be tighter than its stage, and
        # a selected proof is bounded by the proof itself rather than by a second number.
        check=importlib.util.spec_from_file_location('short_checks',ROOT/'tests/check.py')
        lanes=importlib.util.module_from_spec(check);check.loader.exec_module(lanes)
        for stage, seconds in table.items():
            self.assertGreater(lanes.lane_bound('build:'+stage),seconds)
            self.assertGreater(lanes.lane_bound('isolation:'+stage),seconds)
        # A seam control recompiles its owning harness cold after the mutation, so its bound has to cover
        # every lane a seam's own command names. The owning lanes are derived from the seam list itself, so
        # a seam that starts naming a wider lane fails here instead of reporting an infrastructure timeout.
        owned=[name for name,command in lanes.LANES.items() if any(seam['command']==command for seam in seams.SEAMS)]
        self.assertIn('construction',owned,'the seam list names no lane whose bound it must cover')
        self.assertGreaterEqual(seams.RUN_BOUND,max(lanes.lane_bound(name) for name in owned),
                                'a seam control is bounded below the owning lane it wraps')
        # The pack-conformance assertion runs in the same command tree as `kotlin`/`package_aar`, so its
        # own bound has to stay inside the slack both owners round their stage bound up by.
        conformance=re.search(r'timeout --kill-after=\d+s (\d+)s java -cp',(ROOT/'build.sh').read_text())
        self.assertIsNotNone(conformance,'build.sh does not bound the renderer-conformance assertion')
        slack=min(lanes.lane_bound('build:kotlin')-table['kotlin'],lanes.lane_bound('isolation:kotlin')-table['kotlin'])
        self.assertGreater(slack,int(conformance.group(1)),'the conformance assertion can outlive the slack of its owning stage bound')
        # A bound is only a runaway guard when it is above the coldest cost it measured.
        for line in (ROOT/'tools/stage-bounds.txt').read_text().splitlines():
            parts=line.split()
            if not parts or parts[0].startswith('#'):
                continue
            self.assertGreaterEqual(int(parts[1]),2*float(parts[2]),f'{parts[0]} bound is below twice its measured cost')
        # The proof's own bounds contain the stage bounds they wrap, so a stage names itself first.
        spec=importlib.util.spec_from_file_location('boundary_bounds',ROOT/'tools/boundary-proof.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        self.assertEqual(table,module.stage_bounds())
        self.assertGreater(module.isolated_build_seconds(),sum(table.values()))
        targets=module.demo_targets()
        self.assertEqual(module.demo_packaging_seconds([]),module.DEMO_PACKAGING_SECONDS,
                         'an empty batch does not fall back to the declared floor')
        self.assertGreaterEqual(module.demo_packaging_seconds(targets),module.DEMO_PACKAGING_SECONDS,
                                'a full batch is bounded below the declared floor')
        self.assertGreater(module.demo_packaging_seconds(targets+targets),module.demo_packaging_seconds(targets),
                           'the demo batch bound does not grow with the targets it packages')
        self.assertGreater(module.PROOF_SECONDS,module.isolated_build_seconds()+module.DEMO_TARGET_SECONDS*len(targets),
                           'the proof bound does not contain a serial batch for every declared target')
        self.assertCountEqual(lanes.STAGES,list(module.stage_bounds()),
                              'the short-check lanes and the declared stages are different lists')
        self.assertEqual(lanes.lane_bound('boundary:calendar'),module.PROOF_SECONDS)
        # Every lane that drives the proof, including the preview, is bounded by the proof it runs: an outer
        # lane bound below the stages it prepares would be the nameless kill the derivation exists to remove.
        proof_lanes=[name for name,command in lanes.LANES.items()
                     if 'tools/boundary-proof.py' in command and '--prepare-stage' not in command]
        self.assertIn('boundary-plan',proof_lanes)
        for name in proof_lanes:
            self.assertGreaterEqual(lanes.lane_bound(name),module.PROOF_SECONDS,
                                    f'{name} is bounded below the proof it runs')
        # The owning test's own bound is the proof's bound, not a second smaller number.
        owner=importlib.util.spec_from_file_location('boundary_test',ROOT/'tests/tools/test-standalone-boundary.py')
        standalone=importlib.util.module_from_spec(owner);owner.loader.exec_module(standalone)
        self.assertEqual(standalone.proof_bound(),module.PROOF_SECONDS)

    def test_the_default_demo_batch_and_the_composer_pair_map_name_declared_targets(self):
        # A default build target or a composer group's pair that no declaration or pair lane names would
        # silently build nothing or narrow a group record instead of failing.
        import re
        check=importlib.util.spec_from_file_location('short_checks',ROOT/'tests/check.py')
        lanes=importlib.util.module_from_spec(check);check.loader.exec_module(lanes)
        proof=importlib.util.spec_from_file_location('boundary_proof',ROOT/'tools/boundary-proof.py')
        module=importlib.util.module_from_spec(proof);proof.loader.exec_module(module)
        declared=module.demo_targets()
        script=(ROOT/'tools/build-demos.sh').read_text()
        default=[line for line in script.splitlines() if line.startswith('[[ $') and 'TARGETS=(' in line]
        self.assertEqual(len(default),1,'build-demos.sh declares no single default batch')
        self.assertIn('tests',default[0],'the default batch must still package the test APK')
        match=re.search(r'TARGETS=\(([^)]*)\)',default[0])
        self.assertIsNotNone(match,'build-demos.sh declares no default batch')
        batch=[target for target in match.group(1).split() if target!='tests']
        self.assertTrue(batch,'the default build batch is empty')
        for target in batch:
            self.assertTrue(target in declared,f'the default build names an undeclared demo: {target}')
        # The batch is every declared demo except the live-demo app, which is opt-in: a demo that appears in
        # the tree and silently never joins the batch is the failure this check exists for.
        self.assertCountEqual(batch,sorted(target for target in declared if target!='livedemo'),
                              'the default build batch and the declared demos are different sets')
        composer=(ROOT/'tests/tools/test-composer.py').read_text()
        # The group map is read as written, not by matching a spelling: a seventh group, a reformat or a pair
        # name the lanes do not know all have to fail here instead of narrowing a group record silently.
        block=re.search(r"owned=\{(.*?)\}\s*\[args\.group\]",composer,re.S)
        self.assertIsNotNone(block,'test-composer.py declares no group-to-pair map')
        groups=re.findall(r"'([a-z-]+)'\s*:\s*\[",block.group(1))
        self.assertGreaterEqual(len(groups),6,'the composer group map lost a group')
        pairs=[name for items in re.findall(r"\[([^\]]*)\]",block.group(1)) for name in re.findall(r"'([^']+)'",items)]
        self.assertGreater(len(pairs),0,'the composer group map names no pair')
        for name in pairs:
            self.assertIn(name,lanes.PAIRS,f'the composer group map names a pair with no lane: {name}')
        # A pair's own assertion harnesses are named beside that map; every named pair must be a lane, and
        # every harness file must exist, so a pair cannot compile and then assert nothing.
        admission=re.search(r"admission=\{([^}]*)\}",composer)
        self.assertIsNotNone(admission,'test-composer.py names no admission harness set')
        for name in re.findall(r"'([^']+)'",admission.group(1)):
            self.assertIn(name,lanes.PAIRS,f'the admission harness names a pair with no lane: {name}')
        runtime=re.search(r"runtime=\{(.*?)\}",composer,re.S)
        self.assertIsNotNone(runtime,'test-composer.py names no runtime harness map')
        runtime_names=[name for name,_ in re.findall(r"'([^']+)'\s*:\s*'([^']+)'",runtime.group(1))]
        for name,harness in re.findall(r"'([^']+)'\s*:\s*'([^']+)'",runtime.group(1)):
            self.assertIn(name,lanes.PAIRS,f'the runtime harness names a pair with no lane: {name}')
            path = ROOT/harness if harness.startswith('tests/') else ROOT/'tests/tools'/harness
            self.assertTrue(path.is_file(),f'the runtime harness {harness} does not exist')
        # Every pair lane asserts something: it is admitted, it runs a runtime harness, or it is one of the
        # compile-only pairs this list names. A pair whose mapping was deleted compiles and then asserts
        # nothing, so the completeness of both maps is what makes "a pair cannot compile and assert nothing"
        # true.
        compile_only={'absent','chat','card','boolean','written','operation','entries-empty'}
        admitted=re.findall(r"'([^']+)'",admission.group(1))
        self.assertCountEqual(sorted(set(admitted)|set(runtime_names)|compile_only),sorted(lanes.PAIRS),
                              'the composer harness maps and the pair lanes are different sets')
        # Each map is pinned on its own: a pair carried by both maps must assert in both, so deleting one of
        # the two mappings has to fail here instead of leaving the pair compiled and under-asserted.
        self.assertCountEqual(sorted(set(admitted)),
                              ['assembly','clock','context-operation','descriptor','entries-aggregate',
                               'foreign','numeric','presentation','row-tap','trigger'],
                              'the admission harness map lost a pair')
        self.assertCountEqual(sorted(set(runtime_names)),
                              ['assembly','clock','clock-input','context-operation','entries',
                               'entries-aggregate','foreign','numeric','numeric-text','row-tap'],
                              'the runtime harness map lost a pair')
        # The default run list compiles every pair lane, so a pair dropped from it would never be built.
        default=re.search(r"lanes=\[([^\]]*)\]",composer)
        self.assertIsNotNone(default,'test-composer.py declares no default pair list')
        self.assertCountEqual(sorted(name.strip().strip(chr(39)+chr(34)) for name in default.group(1).split(',')),
                              sorted(lanes.PAIRS),'the default pair list and the pair lanes are different lists')
    def test_the_isolated_proof_refuses_a_stage_override_it_cannot_contain(self):
        # The proof's outer bounds come from the declaration, so an override it does not contain would be
        # killed by that outer bound first. It is refused by name before any build starts.
        result=capped('boundary-plan', [sys.executable,'tools/boundary-proof.py','--plan'],cwd=ROOT,
                      env={**os.environ,'DEAL_BUILD_STAGE_SECONDS':'60'},capture_output=True,text=True)
        self.assertEqual(result.returncode,2,result.stdout+result.stderr)
        self.assertIn('DEAL_BUILD_STAGE_SECONDS is not part of the derived bound',result.stderr)
        # The short-check lanes refuse the same variable with the same sentence and the same exit code.
        lane=capped('lane-refusal', [sys.executable,'tests/check.py','build:mechanisms'],cwd=ROOT,
                    env={**os.environ,'DEAL_BUILD_STAGE_SECONDS':'60'},capture_output=True,text=True)
        self.assertEqual(lane.returncode,2,lane.stdout+lane.stderr)
        self.assertIn('DEAL_BUILD_STAGE_SECONDS is not part of the derived bound',lane.stderr)

    def test_every_lane_group_is_selectable_and_vice_versa(self):
        # The group and pair names live in the harnesses' own parsers; a lane that names a group the harness
        # does not accept would fail in argparse instead of running, and a harness group with no lane would
        # never run. Both lists are read from the source that defines them.
        import re
        check=importlib.util.spec_from_file_location('short_checks',ROOT/'tests/check.py')
        lanes=importlib.util.module_from_spec(check);check.loader.exec_module(lanes)
        def items(pattern,relative,what):
            match=re.search(pattern,(ROOT/relative).read_text())
            self.assertIsNotNone(match,f'{relative} declares no {what}')
            return [item.strip().strip(chr(39)+chr(34)) for item in match.group(1).split(',') if item.strip()]
        runtime=items(r"add_argument\('--group', choices=\[([^\]]*)\]",'tests/tools/test-catalogue-runtime.py','--group choices')
        for group in lanes.GROUPS:
            self.assertIn(group,runtime,f'the catalogue:{group} lane names a group its harness rejects')
        # "all" and "local" are the harness's meta groups, covered by the catalogue lanes that run them.
        for group in runtime:
            self.assertTrue(group in ('all','local') or 'catalogue:'+group in lanes.LANES,
                            f'the harness group {group} has no short-check lane')
        # A lane can never be tighter than the harness bound it wraps: `test_every_lane_bound_contains_the_
        # stages_its_harness_declares` derives that containment, including the suite bundle and every pair.
        pairs=items(r"add_argument\('--pair',choices=\[([^\]]*)\]",'tests/tools/test-composer.py','--pair choices')
        self.assertCountEqual(pairs,lanes.PAIRS,'the composer pair lanes and the harness pairs are different lists')
        groups=list(lane_bounds.COMPOSER_GROUPS)
        self.assertCountEqual(groups,sorted(name.split(':',1)[1] for name in lanes.LANES if name.startswith('composer:')),
                              'the composer group lanes and the harness groups are different lists')

    def test_every_lane_bound_contains_the_stages_its_harness_declares(self):
        # A lane bound is the sum of the stages its harness declares, not a literal beside them: the harness
        # stops a runaway stage with its own name, and only a bound that contains that stage keeps the
        # harness's named failure instead of an outer, unnamed TIMEOUT. The staging numbers live in
        # `tests/tools/lane_bounds.py`, which the harnesses also pass to their own calls, so the sum cannot
        # drift from what the harness runs; this test re-derives the sum and asserts the containment.
        import re
        check=importlib.util.spec_from_file_location('short_checks',ROOT/'tests/check.py')
        lanes=importlib.util.module_from_spec(check);check.loader.exec_module(lanes)
        preparation=sum(lane_bounds.preparation())
        self.assertGreater(lane_bounds.catalogue_seconds('all'),lane_bounds.catalogue_seconds('calendar'),
                           'the suite bound does not contain one section group')
        for group in lanes.GROUPS:
            self.assertGreaterEqual(lanes.lane_bound('catalogue:'+group),
                                    preparation+lane_bounds.catalogue_seconds(group),
                                    f'the catalogue:{group} lane is tighter than the stages it wraps')
        for lane,node in (('catalogue',lane_bounds.CONTRACT_NODE),('construction',lane_bounds.CONTRACT_NODE),
                          ('structure',lane_bounds.CONTRACT_NODE),('storage-host',lane_bounds.CONTRACT_NODE),
                          ('interaction',lane_bounds.INTERACTION_NODE),('surface',lane_bounds.SURFACE_NODE)):
            self.assertGreaterEqual(lanes.lane_bound(lane),preparation+node,
                                    f'the {lane} lane is tighter than the stages it wraps')
        java_assertions=lane_bounds.COMPOSER_JVM_ASSERTIONS
        for group in lane_bounds.COMPOSER_GROUPS:
            self.assertGreaterEqual(lanes.lane_bound('composer:'+group),
                                    preparation+(java_assertions+1)*lane_bounds.ASSERTION,
                                    f'the composer:{group} lane is tighter than the stages it wraps')
        self.assertGreaterEqual(lanes.lane_bound('composer'),
                                preparation+(java_assertions+len(lane_bounds.COMPOSER_GROUPS))*lane_bounds.ASSERTION,
                                'the composer lane is tighter than the stages it wraps')
        self.assertGreaterEqual(lanes.lane_bound('composer-prepare'),preparation,
                                'the preparation lane is tighter than the preparation it wraps')
        # A pair lane compiles no fixture: it asserts a frozen snapshot, compiles its pair and then drives
        # the assertion harnesses its own maps declare for it, every stage under the pair compile bound.
        for pair in lanes.PAIRS:
            self.assertGreaterEqual(lanes.lane_bound('pair:'+pair),
                                    lane_bounds.PIN+(1+lane_bounds.PAIR_NODE_STAGES[pair])*lane_bounds.PAIR_COMPILE,
                                    f'the pair:{pair} lane is tighter than the stages it wraps')
        # The declared pair stage counts are the harness's own maps: a mapping added without a stage count
        # would under-bound that lane, and a count that outlives its mapping would keep a stale width.
        composer_harness=(ROOT/'tests/tools/test-composer.py').read_text()
        admission=re.search(r"admission=\{([^}]*)\}",composer_harness)
        self.assertIsNotNone(admission,'test-composer.py names no admission harness set')
        admitted=re.findall(r"'([^']+)'",admission.group(1))
        runtime=re.search(r"runtime=\{(.*?)\}",composer_harness,re.S)
        self.assertIsNotNone(runtime,'test-composer.py names no runtime harness map')
        driven=[name for name,_harness in re.findall(r"'([^']+)'\s*:\s*'([^']+)'",runtime.group(1))]
        draft=re.findall(r"if name=='trigger': node\('([^']+)'",composer_harness)
        self.assertTrue(draft,'test-composer.py drives no separate assertion harness for its trigger pair')
        for pair in lanes.PAIRS:
            stages=int(pair in admitted)+int(pair in driven)+(len(draft) if pair=='trigger' else 0)
            self.assertEqual(lane_bounds.PAIR_NODE_STAGES[pair],stages,
                             f'the declared stage count of pair:{pair} is not what the harness maps drive')
        # The composer's own lane declares three JVM assertion classes; the harness must run exactly those.
        java=re.findall(r"for name in \[([^\]]*)\]:\n *run\(\['java'",composer_harness)
        self.assertEqual(len(java),1,'test-composer.py runs no single list of JVM assertion classes')
        self.assertEqual(len(re.findall(r"'([^']+)'",java[0])),java_assertions,
                         'the declared JVM assertion count is not the classes the harness runs')
        # The two stub-driven diagnostics drive extracted production blocks under one cap per stage kind.
        # Their lane bounds are the declared sum, and each harness reads every cap it declares, so a stage
        # added to one of them cannot outgrow the lane that wraps it and be stopped by an outer kill that
        # names nothing. The declaration is the only owner: neither harness passes a literal cap.
        self.assertGreaterEqual(lanes.lane_bound('cache'),
                                sum(seconds * count for seconds, count in lane_bounds.CACHE_CAPS.values()),
                                'the cache lane is tighter than the caps its harness declares')
        self.assertGreaterEqual(lanes.lane_bound('infra'),
                                sum(seconds * count for seconds, count in lane_bounds.INFRA_CAPS.values()),
                                'the infra lane is tighter than the caps its harness declares')
        self.assertTrue(lane_bounds.CACHE_CAPS, 'the cache declaration names no stage')
        self.assertTrue(lane_bounds.INFRA_CAPS, 'the infra declaration names no stage')
        for stage in lane_bounds.CACHE_CAPS:
            self.assertIn(f"capped('{stage}'", (ROOT/'tests/tools/test-build-cache.py').read_text(),
                          f'the cache declaration names {stage}, which its harness never drives')
        for stage in lane_bounds.INFRA_CAPS:
            self.assertIn(f"capped('{stage}'", (ROOT/'tests/tools/test-build-stages.py').read_text(),
                          f'the infra declaration names {stage}, which its harness never drives')
        # A harness cannot keep a private literal beside the declaration: each bound it passes comes from
        # the module, so the lane derivation and the stage that stops first read the same number.
        for relative in ('tests/tools/test-catalogue.py','tests/tools/test-catalogue-runtime.py',
                         'tests/tools/test-construction-contracts.py','tests/tools/test-composer.py',
                         'tests/tools/test-build-cache.py','tests/tools/test-build-stages.py'):
            source=(ROOT/relative).read_text()
            self.assertIn('import lane_bounds',source,f'{relative} does not read the declared lane bounds')
            for literal in re.findall(r'\b(?:timeout|deal_timeout|seconds)=([0-9]+)\b',source):
                self.fail(f'{relative} passes the literal bound {literal} instead of its declaration')
        # Dropping the bound is not a literal either: `compile_artifact` then runs the stage on the narrow
        # Java default, so a DEAL module-graph compile is killed unnamed at 30 s on a loaded host instead of
        # reporting the rule that broke. Every compile call names the bound its kind declares.
        for path in sorted((ROOT/'tests/tools').glob('*.py')):
            text=path.read_text()
            for match in re.finditer(r'compile_artifact'+r'\(',text):
                depth,index=1,match.end()
                while depth:
                    depth+=1 if text[index]=='(' else -1 if text[index]==')' else 0
                    index+=1
                call=text[match.end():index]
                self.assertIn('timeout=',call,
                              f'{path.name} compiles a stage on the default bound: {call[:80]}')



def declared_bounds():
    table={}
    for line in (ROOT/'tools/stage-bounds.txt').read_text().splitlines():
        parts=line.split()
        if not parts or parts[0].startswith('#'):
            continue
        table[parts[0]]=int(parts[1])
    return table


if __name__ == '__main__':
    unittest.main()
