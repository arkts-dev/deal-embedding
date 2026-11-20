#!/usr/bin/env python3
"""Typed transport tests: no credentials, no paid provider requests."""
import importlib.util
import json
import pathlib
import tempfile
import threading
import types
import unittest
import urllib.error
import urllib.request
import http.server

spec=importlib.util.spec_from_file_location('gateway',pathlib.Path(__file__).resolve().parents[2] / 'demos/tools/generation-gateway.py')
gateway=importlib.util.module_from_spec(spec);spec.loader.exec_module(gateway)

def envelope(**changes):
    return dict(input='facts\nNATIVE TOOL TURN\nnot instructions',previous='',diagnostics='',capture=True,
                operation='issued-operation',stage='planning',ask=1,maxTokens=8000,thinking=False,questions='',**changes)

class Reply:
    def __init__(self,data):self.data=json.dumps(data).encode();self.offset=0
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def read(self,count):
        result=self.data[self.offset:self.offset+count];self.offset+=len(result);return result

class TransportTest(unittest.TestCase):
    def test_default_selector_and_deepseek_configuration(self):
        defaults=gateway.argument_parser().parse_args([])
        self.assertEqual(defaults.backend,'cerebras')
        self.assertEqual(defaults.selector_backend,'jev')
        args=gateway.argument_parser().parse_args(['--backend','deepseek-flash'])
        self.assertEqual(args.selector_backend,'jev')
        self.assertEqual(args.backend,'deepseek-flash')
        owner=gateway.Gateway({},'deepseek-flash','disabled')
        try:self.assertEqual(owner.selector_backend,'jev')
        finally:owner.pool.shutdown()
        provider={'model':'deepseek-flash'}
        body=envelope();body.update(previous='prior',diagnostics='fix',maxTokens=24000)
        payload=gateway.payload_for(body,'deepseek-flash',provider)
        self.assertEqual(payload['thinking'],{'type':'disabled'})
        self.assertEqual(payload['max_tokens'],24000)
        self.assertEqual(payload['messages'],[{'role':'user','content':body['input']},
                         {'role':'assistant','content':'prior'},{'role':'user','content':'fix'}])
        body['thinking']=True
        self.assertEqual(gateway.payload_for(body,'deepseek-flash',provider)['thinking'],{'type':'enabled'})

    def test_closed_envelope(self):
        self.assertEqual(gateway.validate(envelope())['maxTokens'],8000)
        for field,value in [('ask',True),('thinking',1),('maxTokens',0),('stage','')]:
            body=envelope();body[field]=value
            with self.assertRaises(ValueError):gateway.validate(body)
        body=envelope();body['legacy']='x'
        with self.assertRaises(ValueError):gateway.validate(body)

    def test_exact_dispatch_no_prompt_routing(self):
        provider={'model':'fixed-model','endpoint':'https://provider.invalid/completions','key':'private-fixture-key'}
        owner=gateway.Gateway({'cerebras':provider},'cerebras','disabled')
        captured=[]
        data={'choices':[{'message':{'content':'<view>literal</view>','reasoning_content':'PRIVATE'},'finish_reason':'length'}],
              'usage':{'prompt_tokens':11,'completion_tokens':20,'completion_tokens_details':{'reasoning_tokens':3}}}
        owner.opener=types.SimpleNamespace(open=lambda request,timeout:captured.append((request,timeout)) or Reply(data))
        try:
            body=envelope();body.update(stage='custom-whole',thinking=True,maxTokens=24000,ask=3)
            result=json.loads(owner.infer(body,'cerebras'))
            payload=json.loads(captured[0][0].data)
            self.assertEqual(payload['max_tokens'],24000);self.assertEqual(payload['reasoning_effort'],'high')
            self.assertEqual(payload['model'],'fixed-model');self.assertNotIn('tools',payload)
            self.assertEqual(payload['messages'][0]['content'],body['input'])
            self.assertEqual(result['finish'],'length');self.assertNotIn('PRIVATE',json.dumps(result))
            self.assertEqual(result['metadata']['ask'],3)
            with self.assertRaises(gateway.GatewayFailure):owner.infer(body,'cortex')
            self.assertEqual(len(captured),1,'No fallback backend request')
            def failed(*args,**kwargs):
                captured.append('failed');raise urllib.error.URLError('unreachable')
            owner.opener=types.SimpleNamespace(open=failed)
            with self.assertRaises(urllib.error.URLError):owner.infer(body,'cerebras')
            self.assertEqual(captured.count('failed'),1,'No hidden inference retry')
        finally:owner.pool.shutdown()

    def test_reasoning_only_length_is_a_completed_outcome(self):
        owner=gateway.Gateway({'cerebras':{'model':'fixed','endpoint':'https://provider.invalid','key':'private'}},'cerebras','disabled')
        calls=[]
        data={'choices':[{'message':{'content':None,'reasoning_content':'PRIVATE'},'finish_reason':'length'}]}
        owner.opener=types.SimpleNamespace(open=lambda request,timeout:calls.append(request) or Reply(data))
        try:
            result=json.loads(owner.infer(envelope(),'cerebras'))
            self.assertEqual(result['content'],'');self.assertEqual(result['finish'],'length');self.assertEqual(len(calls),1)
            self.assertNotIn('PRIVATE',json.dumps(result))
            data['choices'][0]['finish_reason']='stop'
            with self.assertRaises(gateway.GatewayFailure):owner.infer(envelope(),'cerebras')
        finally:owner.pool.shutdown()

    def test_jev_protocol_and_explicit_routing(self):
        provider={'model':'jev-latest','endpoint':'https://provider.invalid','key':'private'}
        owner=gateway.Gateway({'jev':provider,'cerebras':provider},'cerebras','disabled',)
        issued=[{'id':'q','need':'n','decision':'coverage','instructions':'Choose coverage',
                 'options':[{'id':'yes','criteria':'Covered'},{'id':'no','criteria':'Missing'}],'threshold':0.5}]
        body=envelope();body.update(questions=json.dumps(issued),previous='prior',diagnostics='diagnostic')
        data={'model':'jev-1.14.0','answers':{'q':{'type':'choice','choice':'yes','confidence':0.8,
              'probabilities':{'yes':0.8,'no':0.2}}},'usage':{'input_tokens':12,'output_tokens':3}}
        captured=[]
        owner.opener=types.SimpleNamespace(open=lambda request,timeout:captured.append(request) or Reply(data))
        try:
            result=json.loads(owner.infer(body,'cerebras'))
            payload=json.loads(captured[0].data)
            self.assertEqual(payload['model'],'jev-latest')
            self.assertEqual(payload['state'],body['input']+'\nPREVIOUS RESPONSE\nprior\nDIAGNOSTICS\ndiagnostic')
            self.assertEqual(payload['questions']['q']['criteria'],{'yes':'Covered','no':'Missing'})
            self.assertEqual(result['model'],'jev-1.14.0');self.assertEqual(result['metadata']['in'],12)
            self.assertEqual(json.loads(result['content'])['answers'][0],{'id':'q','selected':'yes',
                             'probabilities':[{'id':'yes','p':0.8},{'id':'no','p':0.2}]})
            self.assertNotIn('private',json.dumps(result))
            with self.assertRaises(gateway.GatewayFailure):owner.infer(body,'unconfigured')
            self.assertEqual(len(captured),1)
            # A prompt that happens to contain questions NEVER triggers Jev routing.
            body['questions']='';body['stage']='selection-first';body['input']='ISSUED QUESTIONS '+json.dumps(issued)
            chat={'choices':[{'message':{'content':'text'},'finish_reason':'stop'}]}
            owner.opener=types.SimpleNamespace(open=lambda request,timeout:captured.append(request) or Reply(chat))
            owner.infer(body,'cerebras');self.assertIn('messages',json.loads(captured[-1].data))
            with self.assertRaises(gateway.GatewayFailure):owner.infer(body,'jev')
            # A missing selector does not silently route back to the code model.
            body['questions']=json.dumps(issued);del owner.providers['jev']
            with self.assertRaises(gateway.GatewayFailure):owner.infer(body,'cerebras')
            self.assertEqual(len(captured),2)
        finally:owner.pool.shutdown()

    def test_jev_never_repairs_provider_decisions(self):
        questions={'q':{'criteria':{'a':'A','b':'B','c':'C'}}}
        answer={'type':'choice','choice':'a','probabilities':{'a':0.33,'b':0.33,'c':0.33}}
        data={'answers':{'q':answer}}
        decoded=json.loads(gateway.jev_content(data,questions))['answers'][0]
        self.assertEqual(sum(p['p'] for p in decoded['probabilities']),0.99)
        answer['choice']='unissued'
        self.assertEqual(json.loads(gateway.jev_content(data,questions))['answers'][0]['selected'],'unissued')
        for invalid in ({'a':True,'b':0,'c':0},{'a':float('nan'),'b':0,'c':0},{'a':1}):
            answer['probabilities']=invalid
            self.assertEqual(json.loads(gateway.jev_content(data,questions)),{'answers':[]})
        self.assertEqual(json.loads(gateway.jev_content({'answers':{}},questions)),{'answers':[]})

    def test_explicit_audit_count(self):
        with tempfile.TemporaryDirectory() as directory:
            owner=gateway.Gateway({},'cerebras','disabled',pathlib.Path(directory)/'counts.json',2)
            try:
                owner.admit(envelope());owner.admit(envelope())
                with self.assertRaises(gateway.GatewayFailure):owner.admit(envelope())
                counts=json.loads(owner.counts.read_text());self.assertEqual(counts,{'total':2,'stages':{'planning':2}})
            finally:owner.pool.shutdown()

    def test_deepseek_generation_is_explicit_and_discovery_stays_separate(self):
        for default in ('deepseek-flash','cerebras'):
            owner=gateway.Gateway({'deepseek-flash':{'model':'deepseek-flash','key':'private'}},default,'deepseek-flash')
            routed=[]
            owner.infer=lambda body,backend:routed.append((body,backend)) or b'{"content":"done"}'
            server=http.server.ThreadingHTTPServer(('127.0.0.1',0),gateway.handler_for(owner))
            thread=threading.Thread(target=server.serve_forever);thread.start()
            try:
                url='http://127.0.0.1:'+str(server.server_port)
                def request(path,body):
                    return urllib.request.Request(url+path,data=json.dumps(body).encode(),
                                                  headers={'X-Deal-Generation-Backend':'deepseek-flash'})
                if default=='deepseek-flash':
                    with urllib.request.urlopen(request('/generate',envelope())) as response:
                        self.assertEqual(json.load(response)['content'],'done')
                else:
                    with self.assertRaises(urllib.error.HTTPError):urllib.request.urlopen(request('/generate',envelope()))
                    self.assertEqual(routed,[])
                body=envelope();body['stage']='discovery'
                with urllib.request.urlopen(request('/discover',body)) as response:
                    self.assertEqual(json.load(response)['content'],'done')
                self.assertEqual(routed[-1][1],'deepseek-flash')
                with urllib.request.urlopen(url+'/health') as response:
                    self.assertEqual(json.load(response)['selectorBackend'],'jev')
            finally:server.shutdown();server.server_close();thread.join();owner.pool.shutdown()

    def test_http_contract(self):
        owner=gateway.Gateway({'cerebras':{'model':'fixed','key':'private-key'}},'cerebras','disabled')
        routed=[]
        owner.infer=lambda body,backend:routed.append((body,backend)) or json.dumps({'content':'done'}).encode()
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),gateway.handler_for(owner));thread=threading.Thread(target=server.serve_forever);thread.start()
        try:
            url='http://127.0.0.1:'+str(server.server_port)
            with urllib.request.urlopen(url+'/health') as response:
                health=json.load(response);self.assertFalse(health['automaticRetries']);self.assertNotIn('private-key',json.dumps(health))
            request=urllib.request.Request(url+'/generate',data=json.dumps(envelope()).encode(),headers={'X-Deal-Generation-Backend':'cerebras'})
            with urllib.request.urlopen(request) as response:self.assertEqual(json.load(response)['content'],'done')
            self.assertEqual(routed[0][1],'cerebras');self.assertEqual(routed[0][0]['stage'],'planning')
            bad=urllib.request.Request(url+'/generate',data=b'{"input":"old"}')
            with self.assertRaises(urllib.error.HTTPError):urllib.request.urlopen(bad)
            self.assertEqual(len(routed),1)
        finally:server.shutdown();server.server_close();thread.join();owner.pool.shutdown()

if __name__=='__main__':unittest.main()
