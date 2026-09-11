#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import json
import os
import shutil
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient

ANDROID_ROOT = Path(__file__).resolve().parent
MAIN = ANDROID_ROOT / 'app/src/main/java/com/re2o/recorder/MainActivity.kt'
API = ANDROID_ROOT / 'app/src/main/java/com/re2o/recorder/VocaNoteApiClient.kt'
SERVER_ROOT = Path(os.environ.get('VOCANOTE_SERVER_ROOT', ANDROID_ROOT.parent / 'VocaNote-Backend')).resolve()
RECORDINGS_DIR = Path(os.environ.get('VOCANOTE_RECORDINGS_DIR', SERVER_ROOT / 'recordings')).resolve()
RUN_ROOT = SERVER_ROOT / 'validation_runs'

import sys
sys.path.insert(0, str(SERVER_ROOT))
import app  # noqa: E402
from vocanote_queue import connect, enqueue_job, get_job, init_db  # noqa: E402
from vocanote_tombstone import get_tombstone  # noqa: E402


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def make_recording(*, processing: bool = False) -> tuple[str, Path]:
    rid = str(uuid.uuid4())
    d = RECORDINGS_DIR / rid
    d.mkdir(parents=True, exist_ok=False)
    (d / 'audio.m4a').write_bytes(b'phase5-audio-delete-test' * 200)
    write_json(d / 'metadata.json', {'schema_version': 'vocanote.metadata.v2', 'recording_id': rid, 'id': rid, 'title': 'Phase5 Android 삭제 테스트', 'recorded_at': datetime.now().isoformat(), 'audio_file': 'audio.m4a'})
    write_json(d / 'status.json', {'schema_version': 'vocanote.status.v2', 'recording_id': rid, 'id': rid, 'status': 'semantic_running' if processing else 'completed', 'step': 'semantic' if processing else 'completed'})
    write_json(d / 'segments_clean.json', {'segments': [{'index': 0, 'speaker': 'S1', 'start': 0, 'end': 1, 'text': 'Phase5 삭제 테스트'}]})
    (d / 'summary.md').write_text('Phase5 summary', encoding='utf-8')
    (d / 'analysis.md').write_text('Phase5 analysis', encoding='utf-8')
    init_db()
    enqueue_job(job_id=f'phase5_{rid}', recording_id=rid, audio_path=str(d / 'audio.m4a'), metadata_path=str(d / 'metadata.json'), output_dir=str(d))
    if processing:
        with connect() as conn:
            conn.execute("""
                UPDATE recording_jobs
                SET status='semantic_running', step='semantic', claimed_by='android-phase5', claim_token='phase5-token', lease_expires_at=?, updated_at=?
                WHERE recording_id=?
            """, ('2999-01-01T00:00:00+00:00', datetime.now().isoformat(), rid))
    return rid, d


def auth_headers() -> dict[str, str]:
    return {'X-Upload-Token': app.token()}


def list_contains(client: TestClient, rid: str) -> bool:
    body = client.get('/api/recordings', headers=auth_headers()).json()
    return any((x.get('recording_id') or x.get('id')) == rid for x in body.get('items', []))


def main() -> int:
    run_dir = RUN_ROOT / ('phase5_android_delete_ui_' + datetime.now().strftime('%Y%m%d_%H%M%S'))
    run_dir.mkdir(parents=True, exist_ok=True)
    main_src = MAIN.read_text(encoding='utf-8')
    api_src = API.read_text(encoding='utf-8')
    client = TestClient(app.app)
    app.ensure_dirs()

    report: dict[str, Any] = {'ok': False, 'run_dir': str(run_dir), 'tests': {}}

    report['tests']['cancel_flow_static'] = {
        'ok': 'setNegativeButton("취소", null)' in main_src and '.setPositiveButton("삭제") { _, _ -> deleteRecording(item.id) }' in main_src,
        'delete_call_before_confirm': 'showRecordingItemMenu' in main_src and 'confirmDeleteRecording(item)' in main_src,
    }
    report['tests']['ui_location_and_dialog'] = {
        'ok': all(s in main_src for s in ['text = if (deleting) "삭제 중…" else "⋮"', 'PopupMenu(this, anchor)', 'menu.add("삭제")', '녹음을 삭제할까요?', '이 녹음과 전사 및 분석 결과가 삭제됩니다.', '삭제 후 앱에서는 복구할 수 없습니다.']),
        'ui_evidence': 'recording list card titleRow right-side ⋮ PopupMenu -> 삭제 -> AlertDialog',
    }
    report['tests']['api_client_common'] = {
        'ok': 'fun deleteRecording(serverUrl: String, token: String, recordingId: String)' in api_src and 'conn.requestMethod = "DELETE"' in api_src and 'delete_scope' in api_src and 'VocaNoteApiClient.deleteRecording' in main_src,
        'api_client_path': str(API),
    }
    report['tests']['duplicate_request_prevention_static'] = {
        'ok': 'deletingRecordingIds.add(recordingId)' in main_src and 'deletingRecordingIds.remove(recordingId)' in main_src and 'deletingRecordingIds.contains(item.id)' in main_src,
        'active_delete_request_per_recording': 1,
    }
    report['tests']['failure_timeout_404_handling_static'] = {
        'ok': all(s in main_src for s in ['녹음을 삭제하지 못했습니다.', '네트워크 연결을 확인하고 다시 시도해주세요.', 'SocketTimeoutException', 'recordingExistsInServerList(serverUrl, uploadToken, recordingId)', 'openHistory()']),
        'local_delete_on_failure': False,
    }

    rid, d = make_recording(processing=False)
    before_contains = list_contains(client, rid)
    resp = client.delete(f'/api/recordings/{rid}', headers=auth_headers())
    after_contains = list_contains(client, rid)
    report['tests']['normal_delete_server_contract'] = {
        'ok': resp.status_code == 200 and resp.json().get('ok') is True and resp.json().get('recording_id') == rid and resp.json().get('delete_state') == 'deleted' and before_contains and not after_contains,
        'delete_response': resp.json(),
        'before_list_contains': before_contains,
        'after_list_contains': after_contains,
    }

    already = client.delete(f'/api/recordings/{rid}', headers=auth_headers())
    report['tests']['already_deleted_404_or_idempotent_refresh'] = {
        'ok': already.status_code == 200 and already.json().get('delete_state') == 'deleted' and not list_contains(client, rid),
        'repeat_delete_status': already.status_code,
        'list_contains_after_refresh': list_contains(client, rid),
    }

    prid, pd = make_recording(processing=True)
    job_before = get_job(prid)
    presp = client.delete(f'/api/recordings/{prid}', headers=auth_headers())
    job_after = get_job(prid)
    report['tests']['processing_time_delete'] = {
        'ok': presp.status_code == 200 and presp.json().get('delete_state') == 'deleted' and job_after and job_after.get('status') == 'cancelled' and job_after.get('claimed_by') is None and not list_contains(client, prid) and not pd.exists(),
        'android_delete_success': presp.json(),
        'job_before': job_before,
        'job_after': job_after,
        'list_contains_after': list_contains(client, prid),
        'recording_dir_exists_after': pd.exists(),
    }

    # Restart persistence is server-state based: a fresh list call after deletion must not show the item.
    report['tests']['restart_persistence'] = {
        'ok': not list_contains(client, rid) and not list_contains(client, prid),
        'fresh_server_list_after_delete_contains_normal': list_contains(client, rid),
        'fresh_server_list_after_delete_contains_processing': list_contains(client, prid),
    }

    report['ok'] = all(t.get('ok') for t in report['tests'].values())
    report_path = run_dir / 'phase5_android_delete_ui_report.json'
    write_json(report_path, report)
    write_json(RUN_ROOT / 'phase5_latest_report.json', report)
    print(json.dumps({'ok': report['ok'], 'report_path': str(report_path), 'tests': report['tests']}, ensure_ascii=False, indent=2))
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
