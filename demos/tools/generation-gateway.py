#!/usr/bin/env python3
"""Explicit-stage loopback inference transport. Credentials stay in this process; no routing by prompt text."""
import argparse
import concurrent.futures
import http.server
import json
import os
import pathlib
import socket
import sys
import subprocess
import time
import urllib.error
import urllib.request

MAX_BYTES = 16 * 1024 * 1024

class GatewayFailure(Exception):
    def __init__(self, code, message, status=503):
        super().__init__(message)
        self.code, self.status = code, status

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate(body):
    required = {'input','previous','diagnostics','capture','operation','stage','ask','maxTokens','thinking','questions'}
    if not isinstance(body, dict) or set(body) != required:
        raise ValueError('Invalid typed inference envelope')
    if any(type(body[key]) is not str for key in ('input','previous','diagnostics','operation','stage','questions')):
        raise ValueError('Expected inference text')
    if not body['operation'] or not body['stage']:
        raise ValueError('Missing operation or stage')
    if any(type(body[key]) is not bool for key in ('capture','thinking')):
        raise ValueError('Expected inference flags')
    if any(type(body[key]) is not int or body[key] <= 0 for key in ('ask','maxTokens')):
        raise ValueError('Expected positive inference counters')
    return body


def messages(body):
    result = [{'role':'user','content':body['input']}]
    if body['previous']:
        result.append({'role':'assistant','content':body['previous']})
    if body['diagnostics']:
        result.append({'role':'user','content':body['diagnostics']})
    return result


def payload_for(body, backend, provider):
    # Stage and budgets are supplied by compiled DEAL. Backend is selected explicitly,
    # never inferred from operation, stage, content, errors or thinking escalation.
    payload = {'model':provider['model'],'messages':messages(body),'max_tokens':body['maxTokens'],
               'temperature':0.2,'stream':False}
    if backend == 'cerebras':
        payload.update(temperature=1, top_p=0.95, reasoning_effort='high' if body['thinking'] else 'low', clear_thinking=True)
    elif backend == 'deepseek-flash':
        payload.update(temperature=0, thinking={'type':'enabled' if body['thinking'] else 'disabled'})
    else:
        payload['reasoning_effort'] = 'high' if body['thinking'] else 'low'
    return payload


def jev_payload(body, model):
    # Questions are a separate typed field issued by DEAL, never parsed from prompts.
    issued = json.loads(body['questions'])
    if not isinstance(issued, list) or not issued:
        raise ValueError('Expected issued selection questions')
    questions = {}
    for question in issued:
        name = question['id']
        if not isinstance(name, str) or not name or name in questions:
            raise ValueError('Invalid question identity')
        criteria = {}
        for option in question['options']:
            key, description = option['id'], option['criteria']
            if not isinstance(key, str) or not key or key in criteria or not isinstance(description, str) or not description:
                raise ValueError('Invalid issued option')
            criteria[key] = description
        if not criteria or not isinstance(question['instructions'], str):
            raise ValueError('Invalid issued question')
        questions[name] = {'type':'choice','instructions':question['instructions'],'criteria':criteria}
    # Step 1 deliberately retains the ENTIRE current prompt, including its spec.
    state = body['input']
    if body['previous']:
        state += '\nPREVIOUS RESPONSE\n' + body['previous']
    if body['diagnostics']:
        state += '\nDIAGNOSTICS\n' + body['diagnostics']
    return {'model':model,'state':state,'questions':questions}


def jev_content(data, questions):
    # Translate representation only. DEAL still validates sum, maximum, ties and
    # thresholds. Never renormalize rounded probabilities or fabricate answers.
    answers = data.get('answers')
    invalid = json.dumps({'answers':[]})
    if not isinstance(answers, dict) or set(answers) != set(questions):
        return invalid
    result = []
    for name in questions:
        answer = answers[name]
        if not isinstance(answer, dict) or answer.get('type') != 'choice' or not isinstance(answer.get('choice'), str):
            return invalid
        probabilities = answer.get('probabilities')
        if not isinstance(probabilities, dict) or set(probabilities) != set(questions[name]['criteria']):
            return invalid
        if any(type(p) not in (int, float) or not 0 <= p <= 1 for p in probabilities.values()):
            return invalid
        result.append({'id':name,'selected':answer['choice'],
                       'probabilities':[{'id':key,'p':probabilities[key]} for key in questions[name]['criteria']]})
    return json.dumps({'answers':result},allow_nan=False)


def strip_reasoning(data):
    for choice in data.get('choices', []):
        for key in ('message','delta'):
            value = choice.get(key)
            if isinstance(value, dict):
                for field in ('reasoning','reasoning_content'):
                    value.pop(field, None)


class Gateway:
    def __init__(self, providers, default_backend, discovery_backend, counts=None, limit=None, deadline=240, selector_backend='jev'):
        self.providers, self.default_backend, self.discovery_backend = providers, default_backend, discovery_backend
        self.counts, self.limit, self.deadline = counts, limit, deadline
        self.selector_backend = selector_backend
        self.opener = urllib.request.build_opener(NoRedirect())
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)

    def admit(self, body):
        if self.counts is None:
            return
        counts = json.loads(self.counts.read_text()) if self.counts.exists() else {'total':0,'stages':{}}
        if self.limit is not None and counts['total'] >= self.limit:
            raise GatewayFailure('AUDIT_LIMIT','Explicit audit call ceiling exhausted')
        counts['total'] += 1
        counts['stages'][body['stage']] = counts['stages'].get(body['stage'],0) + 1
        self.counts.parent.mkdir(parents=True,exist_ok=True)
        temporary = self.counts.with_suffix('.tmp')
        temporary.write_text(json.dumps(counts)); temporary.replace(self.counts)

    def infer(self, body, backend):
        entered = time.monotonic()
        if backend not in self.providers:
            raise GatewayFailure('BACKEND_UNAVAILABLE','Requested configured backend is not enabled')
        if body['questions'] and self.selector_backend is not None:
            backend = self.selector_backend
        if backend == 'jev' and not body['questions']:
            raise GatewayFailure('BACKEND_UNAVAILABLE','Jev requires issued selection questions')
        if backend not in self.providers:
            raise GatewayFailure('BACKEND_UNAVAILABLE','Requested configured backend is not enabled')
        self.admit(body)
        provider = self.providers[backend]
        payload = jev_payload(body,provider['model']) if backend == 'jev' else payload_for(body,backend,provider)
        encoded = json.dumps(payload).encode()
        if len(encoded) > MAX_BYTES:
            raise GatewayFailure('GATEWAY_REQUEST_LIMIT','Provider envelope exceeds byte limit',413)
        request = urllib.request.Request(provider['endpoint'],data=encoded,headers={
            'Content-Type':'application/json','Authorization':'Bearer '+provider['key'],
            'User-Agent':'deal-embed-development/1.0'})
        started = time.monotonic()
        with self.opener.open(request,timeout=self.deadline) as response:
            headers_ms = (time.monotonic()-started)*1000
            chunks = bytearray()
            while True:
                if time.monotonic()-started >= self.deadline:
                    raise GatewayFailure('MODEL_TIMEOUT','Provider deadline exceeded',504)
                chunk = response.read(min(8192, MAX_BYTES+1-len(chunks)))
                if not chunk:
                    break
                chunks.extend(chunk)
                if len(chunks) > MAX_BYTES:
                    raise GatewayFailure('GATEWAY_RESPONSE_LIMIT','Provider response exceeds byte limit',502)
            data = json.loads(chunks)
        strip_reasoning(data)
        if backend == 'jev':
            choice = {'message':{'content':jev_content(data,payload['questions'])},'finish_reason':'stop'}
        else:
            choice = data['choices'][0]
        message = choice['message']
        content, refusal = message.get('content'), message.get('refusal') or ''
        finish = choice.get('finish_reason')
        # A reasoning model can exhaust its issued token cap before producing
        # text. Preserve that completed, charged model outcome for DEAL decoding;
        # it is not a transport failure and does not authorize a hidden retry.
        if content is None and (refusal or finish == 'length'):
            content = ''
        if not isinstance(content,str) or not isinstance(refusal,str) or message.get('tool_calls'):
            raise GatewayFailure('GATEWAY_FAILURE','Expected complete text model response',502)
        if finish not in ('stop','length','content_filter'):
            finish = 'unknown'
        usage = data.get('usage') or {}
        def count(value):
            return value if type(value) is int and 0 <= value <= 2147483647 else None
        complete_ms = (time.monotonic()-started)*1000
        metadata = {'in':count(usage.get('input_tokens') if backend == 'jev' else usage.get('prompt_tokens')), 'out':count(usage.get('output_tokens') if backend == 'jev' else usage.get('completion_tokens')),
                    'cached':count((usage.get('prompt_tokens_details') or {}).get('cached_tokens',usage.get('prompt_cache_hit_tokens'))),
                    'reasoning':count((usage.get('completion_tokens_details') or {}).get('reasoning_tokens')),
                    'finish':finish,'max_tokens':body['maxTokens'],'thinkingRequested':body['thinking'],
                    'operation':body['operation'],'stage':body['stage'],'ask':body['ask'],
                    'totalMs':complete_ms,'receiptTiming':{'requestPreparationMs':(started-entered)*1000,
                    'responseHeadersMs':headers_ms,'responseCompleteMs':complete_ms}}
        result = {'content':content,'finish':finish,'refusal':refusal,'model':data.get('model',provider['model']),'metadata':metadata}
        if body['capture']:
            result.update(request=payload,response=data)
            if len(json.dumps(result).encode()) > MAX_BYTES:
                del result['request']; del result['response']; result['captureOmitted'] = True
        return json.dumps(result).encode()


def handler_for(gateway):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self,*args):
            pass
        def reply(self,status,body):
            output = json.dumps(body).encode()
            self.send_response(status); self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(output))); self.end_headers(); self.wfile.write(output)
        def do_GET(self):
            if self.path != '/health':
                self.send_error(404); return
            self.reply(200,{'configured':True,'automaticRetries':False,'defaultBackend':gateway.default_backend,
                           'discoveryBackend':gateway.discovery_backend,'selectorBackend':gateway.selector_backend,'models':{k:v['model'] for k,v in gateway.providers.items()}})
        def do_POST(self):
            if self.path not in ('/generate','/discover'):
                self.send_error(404); return
            self.connection.settimeout(gateway.deadline)
            try:
                length = int(self.headers.get('Content-Length','0'))
                if not 0 < length <= MAX_BYTES or self.headers.get('Transfer-Encoding'):
                    raise GatewayFailure('GATEWAY_REQUEST_LIMIT','Invalid bounded request length',413)
                body = validate(json.loads(self.rfile.read(length)))
                backend = self.headers.get('X-Deal-Generation-Backend',gateway.default_backend)
                if self.path == '/discover':
                    if backend != gateway.discovery_backend or body['stage'] != 'discovery':
                        raise GatewayFailure('BACKEND_UNAVAILABLE','Requested discovery backend is not enabled')
                elif backend == 'deepseek-flash' and gateway.default_backend != 'deepseek-flash':
                    raise GatewayFailure('BACKEND_UNAVAILABLE','Requested generation backend is not enabled')
                output = gateway.pool.submit(gateway.infer,body,backend).result(timeout=gateway.deadline)
                self.send_response(200); self.send_header('Content-Type','application/json')
                self.send_header('Content-Length',str(len(output))); self.end_headers(); self.wfile.write(output)
            except (BrokenPipeError,ConnectionResetError):
                pass
            except GatewayFailure as error:
                self.reply(error.status,{'error':{'code':error.code,'message':str(error)}})
            except urllib.error.HTTPError as error:
                self.reply(502,{'error':{'code':'PROVIDER_HTTP','message':'Provider rejected request','providerStatus':error.code}})
            except (TimeoutError,socket.timeout,concurrent.futures.TimeoutError):
                self.reply(504,{'error':{'code':'MODEL_TIMEOUT','message':'Provider deadline exceeded'}})
            except urllib.error.URLError:
                self.reply(503,{'error':{'code':'PROVIDER_UNREACHABLE','message':'Provider unavailable'}})
            except Exception as error:
                # Bounded exception type only: never log provider bodies, request
                # content, URLs, credentials or arbitrary exception messages.
                print('Generation transport failure: '+type(error).__name__,file=sys.stderr,flush=True)
                self.reply(502,{'error':{'code':'GATEWAY_FAILURE','message':'Invalid inference transport'}})
    return Handler


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend',choices=('cortex','cerebras','deepseek-flash'),default='cerebras')
    parser.add_argument('--enable-cortex',action='store_true'); parser.add_argument('--enable-cerebras',action='store_true')
    parser.add_argument('--discovery-backend',choices=('disabled','deepseek-flash'),default='disabled')
    parser.add_argument('--cerebras-key-file',type=pathlib.Path,default=pathlib.Path.home()/'cerebras.key')
    parser.add_argument('--selector-backend',choices=('jev',),default='jev',help='Backend for DEAL-issued bounded questions (default: Jev); no fallback')
    parser.add_argument('--jev-key-file',type=pathlib.Path,default=pathlib.Path.home()/'jev.key')
    parser.add_argument('--jev-model',default='jev-latest')
    parser.add_argument('--counts',type=pathlib.Path); parser.add_argument('--audit',action='store_true')
    parser.add_argument('--limit',type=int); parser.add_argument('--deadline',type=float,default=240)
    parser.add_argument('--port',type=int,default=8787)
    return parser


def main():
    parser = argument_parser()
    args = parser.parse_args()
    if not args.deadline > 0 or args.audit and (args.counts is None or args.limit is None or args.limit <= 0) or not args.audit and args.limit is not None:
        parser.error('Audit needs counts/positive limit; deadline must be positive')
    providers = {}
    if args.backend == 'cortex' or args.enable_cortex:
        config = json.loads((pathlib.Path.home()/'.pi/agent/models.json').read_text())['providers']['devagent']
        key = config['apiKey']
        key = subprocess.check_output(key[1:],shell=True,text=True).strip() if key.startswith('!') else os.environ.get(key,key)
        providers['cortex'] = {'key':key,'model':'cortex','endpoint':config['baseUrl'].rstrip('/')+'/chat/completions'}
    if args.backend == 'cerebras' or args.enable_cerebras:
        providers['cerebras'] = {'key':args.cerebras_key_file.read_text().strip(),'model':'qwen-3.8-27b','endpoint':'https://api.cerebras.ai/v1/chat/completions'}
    if args.backend == 'deepseek-flash' or args.discovery_backend == 'deepseek-flash':
        providers['deepseek-flash'] = {'key':os.environ.get('DEXTER_DEEPSEEK_API_KEY',''),'model':'deepseek-flash','endpoint':'https://api.deepseek.com/chat/completions'}
    if args.selector_backend == 'jev':
        providers['jev'] = {'key':args.jev_key_file.read_text().strip(),'model':args.jev_model,'endpoint':'https://api.typesafe.ai/v1/systemone'}
    if any(not p['key'] or any(c.isspace() for c in p['key']) for p in providers.values()):
        parser.error('Enabled backend requires nonempty single-token credential')
    gateway = Gateway(providers,args.backend,args.discovery_backend,args.counts,args.limit,args.deadline,selector_backend=args.selector_backend)
    server = http.server.ThreadingHTTPServer(('127.0.0.1',args.port),handler_for(gateway))
    try:
        server.serve_forever()
    finally:
        server.server_close(); gateway.pool.shutdown(wait=False,cancel_futures=True)

if __name__ == '__main__':
    main()
