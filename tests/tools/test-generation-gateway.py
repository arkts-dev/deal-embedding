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
                operation='issued-operation',stage='planning',ask=1,maxTokens=8000,thinking=False,**changes)

class Reply:
    def __init__(self,data):self.data=json.dumps(data).encode();self.offset=0
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def read(self,count):
        result=self.data[self.offset:self.offset+count];self.offset+=len(result);return result

class TransportTest(unittest.TestCase):
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

    def test_explicit_audit_count(self):
        with tempfile.TemporaryDirectory() as directory:
            owner=gateway.Gateway({},'cerebras','disabled',pathlib.Path(directory)/'counts.json',2)
            try:
                owner.admit(envelope());owner.admit(envelope())
                with self.assertRaises(gateway.GatewayFailure):owner.admit(envelope())
                counts=json.loads(owner.counts.read_text());self.assertEqual(counts,{'total':2,'stages':{'planning':2}})
            finally:owner.pool.shutdown()

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
