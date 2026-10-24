"""Ejecuta solo ProduccionInicializacionTests en un PG18 NUEVO y loopback, sin Docker/SSH."""
from pathlib import Path
import argparse
import json
import os
import shutil
import socket
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pg-bin', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    work = Path(tempfile.mkdtemp(prefix='novagest-primera-inicializacion-'))
    data = work / 'data'
    binary = lambda n: str(args.pg_bin / (n + ('.exe' if os.name == 'nt' else '')))
    env = {k: v for k, v in os.environ.items() if not k.startswith(('PG', 'DB_', 'FLYWAY_', 'SPRING_'))}
    env.update(PGPASSFILE=str(work / 'sin-pgpass'), PGCONNECT_TIMEOUT='3')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    started = False
    result = {'solo_sintetico': True, 'ssh_docker_o_produccion_real': False, 'work': str(work)}

    def run(cmd, **kw):
        return subprocess.run(cmd, env=env, creationflags=flags, check=True, timeout=45, **kw)

    try:
        version = run([binary('postgres'), '--version'], capture_output=True).stdout.decode().strip()
        assert 'PostgreSQL) 18.' in version
        result['version'] = version
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
        assert port != 5432
        run([binary('initdb'), '-D', str(data), '-U', 'admin_fixture', '--auth=trust', '--encoding=UTF8', '--no-locale'], capture_output=True)
        with (data / 'postgresql.conf').open('a', encoding='utf-8') as f:
            f.write(f"\nlisten_addresses='127.0.0.1'\nport={port}\n")
        run([binary('pg_ctl'), '-D', str(data), '-l', str(work / 'postgres.log'), '-w', 'start'],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        started = True
        mvn = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn')
        assert mvn
        command = [mvn, '-o', '-B', '-Dtest=ProduccionInicializacionTests',
                   f'-Dnovagest.pg.fixture.port={port}', f'-Dnovagest.pg.fixture.directory={data}', 'test']
        log = repo / 'target/inicializacion-produccion.log'
        log.parent.mkdir(exist_ok=True)
        with log.open('wb') as f:
            p = subprocess.run(command, cwd=repo, env=env, stdout=f, stderr=subprocess.STDOUT,
                               creationflags=flags, timeout=420)
        result['maven_rc'] = p.returncode
        report = repo / 'target/surefire-reports/TEST-com.proyecto.tramites.ProduccionInicializacionTests.xml'
        if report.exists():
            result['junit'] = ET.parse(report).getroot().attrib
        assert p.returncode == 0, 'Maven fallo: consultar target/inicializacion-produccion.log'
        result['ok'] = True
    finally:
        if started:
            run([binary('pg_ctl'), '-D', str(data), '-m', 'fast', '-w', 'stop'], capture_output=True)
        result['cluster_detenido'] = not (data / 'postmaster.pid').exists()
        (repo / 'target/resultado-inicializacion-produccion.json').write_text(json.dumps(result, indent=2), 'utf-8')
        print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
