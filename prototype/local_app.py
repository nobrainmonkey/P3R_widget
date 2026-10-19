"""Local-only experimental interface; no external service or Android release changes."""
import argparse
from dataclasses import asdict
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
from urllib.parse import urlparse
from state_store import StateStore, StateConflict, InvalidObservation
from planner_service import PlannerService
from real_rules_adapter import load_january_pack

class Application:
    def __init__(self, database):
        self.pack=load_january_pack(); rules=self.pack['rules']
        self.store=StateStore(database,stage_ceilings={s.id:s.floor_ceiling for s in rules.stages},
                              stage_windows={s.id:(s.start,s.end) for s in rules.stages})
        self.service=PlannerService(self.store,rules,self.pack['preflight'])
    def state(self):
        row=self.store.db.execute('SELECT 1 FROM runs WHERE run_id=?',('local',)).fetchone()
        return dict(snapshot=self.store.get_snapshot('local') if row else None,
                    preferences=self.store.get_preferences('local'),coverage=self.pack['coverage_note'],
                    labels=self.pack['labels'],first_slot=self.pack['first_slot'],
                    rules_version=self.pack['rules'].version)
    def command(self,path,payload):
        data=dict(payload); command=data.pop('command_id'); revision=data.pop('expected_revision')
        if path=='/api/baseline':
            baseline=data['baseline']
            if self.state()['snapshot'] is None:
                if revision is not None: raise StateConflict('No existing baseline')
                self.store.create_run('local',baseline)
            receipt=self.store.reenter('local',command,0 if revision is None else revision,baseline)
            return dict(observation=asdict(receipt),replan_pending=True)
        if path=='/api/preferences':
            receipt=self.store.set_preferences('local',command,revision,**data)
            return dict(observation=asdict(receipt),planning=self.service.replan('local'))
        if path=='/api/return':
            return self.service.return_and_replan('local',command,revision,**data)
        raise InvalidObservation('Unknown command')

def make_handler(app):
    class Handler(BaseHTTPRequestHandler):
        def output(self,value,status=200):
            encoded=json.dumps(value,ensure_ascii=False).encode()
            self.send_response(status); self.send_header('Content-Type','application/json; charset=utf-8')
            self.send_header('Cache-Control','no-store'); self.send_header('Content-Length',str(len(encoded)))
            self.end_headers(); self.wfile.write(encoded)
        def local_host(self):
            host=self.headers.get('Host','').split(':')[0]
            return host in {'127.0.0.1','localhost'}
        def do_GET(self):
            if not self.local_host():
                self.output({'error':'Loopback host required'},403);return
            try:
                if self.path=='/':
                    body=(Path(__file__).parent/'ui.html').read_bytes()
                    self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8')
                    self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
                elif self.path=='/api/state':self.output(app.state())
                elif self.path=='/api/plan':
                    if not app.state()['snapshot'] or app.store.get_preferences('local') is None:
                        self.output({'plan':None,'plan_revision':None})
                    else:self.output(app.service.replan('local'))
                elif self.path=='/api/export':self.output(app.store.export_run('local'))
                else:self.output({'error':'Not found'},404)
            except (ValueError,KeyError,StateConflict) as error:self.output({'error':str(error)},400)
        def do_POST(self):
            # Local UI only. Reject cross-origin form/fetch writes and non-JSON bodies.
            if not self.local_host():
                self.output({'error':'Loopback host required'},403);return
            origin=self.headers.get('Origin')
            if origin and origin != 'http://'+self.headers.get('Host',''):
                self.output({'error':'Cross-origin commands rejected'},403);return
            if self.headers.get_content_type()!='application/json':
                self.output({'error':'JSON required'},415);return
            try:
                length=int(self.headers.get('Content-Length','0'))
                if not 0<length<=65536:raise ValueError('Invalid request size')
                payload=json.loads(self.rfile.read(length))
                self.output(app.command(self.path,payload))
            except StateConflict as error:self.output({'error':str(error),'code':'stale_state'},409)
            except (ValueError,KeyError,TypeError) as error:self.output({'error':str(error)},400)
    return Handler

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database',default=str(Path(__file__).parent/'local_state.sqlite'))
    parser.add_argument('--port',type=int,default=8765);args=parser.parse_args()
    app=Application(args.database)
    server=HTTPServer(('127.0.0.1',args.port),make_handler(app))
    print(f'Local experimental planner: http://127.0.0.1:{args.port}',flush=True)
    try:server.serve_forever()
    finally:server.server_close();app.store.close()
if __name__=='__main__':main()
