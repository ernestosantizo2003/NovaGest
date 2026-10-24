"""Regresiones del respaldo de tramites-ctl, sin Docker/SSH ni base real.

Mocks de fallos mas pg_dump/pg_restore reales en un cluster nuevo de loopback.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import socket
import stat
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pg-bin', type=Path, required=True)
    parser.add_argument('--bash', default=None)
    parser.add_argument('--solo-mocks', action='store_true', help='Sin servidor: permite comprobar modos POSIX en Linux')
    parser.add_argument('--output', type=Path, help='JSON opcional; por defecto queda en el laboratorio temporal')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    bash = args.bash or (str(Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Git/bin/bash.exe')
                         if os.name == 'nt' else shutil.which('bash'))
    source = (repo / 'deploy/adrian/server/tramites-ctl').read_text('utf-8')
    function = source[source.index('backup_before_migration() {'):source.index('\n# Levanta la etiqueta pedida;')]
    work = Path(tempfile.mkdtemp(prefix='novagest-respaldo-'))
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    env = {k: v for k, v in os.environ.items() if not k.startswith(('PG', 'DB_', 'FLYWAY_'))}
    env.update(PGPASSFILE=str(work / 'sin-pgpass'), PGCONNECT_TIMEOUT='3', PGCLIENTENCODING='UTF8')
    started = False
    cases = []
    result = {'work': str(work), 'solo_sintetico': True, 'ssh_docker_o_produccion_real': False}
    binary = lambda n: str(args.pg_bin / (n + ('.exe' if os.name == 'nt' else '')))

    def run(cmd, data=None, ok=True):
        p = subprocess.run(list(map(str, cmd)), input=data, capture_output=True, env=env, creationflags=flags, timeout=45)
        if ok and p.returncode:
            raise AssertionError(p.stderr.decode('utf-8', 'replace'))
        return p

    def check(name, ok):
        if not ok:
            raise AssertionError(name)
        cases.append({'caso': name, 'ok': True})

    def shell(case, environment='production', keep='10', missing_user=False, missing_password=False, real=False):
        directory = work / (case + ' con espacios')
        directory.mkdir(exist_ok=True)
        script = work / (case + '.sh')
        # Intercepta solo comandos de PostgreSQL. En Windows adapta /dev/null al dispositivo NUL.
        harness = r'''
set -euo pipefail
umask 022
LAST_BACKUP=""
die() { echo "ERROR: $*" >&2; exit 1; }
log() { :; }
envval() {
 case "$1" in
 DB_HOST) echo 127.0.0.1 ;; DB_PORT) echo "$PORT" ;; DB_NAME) echo fixture ;;
 FLYWAY_USER) [[ "$MISSING_USER" == 1 ]] || echo migrador_fixture ;;
 FLYWAY_PASSWORD) [[ "$MISSING_PASSWORD" == 1 ]] || echo sintetica ;;
 DB_USER) echo app_no_utilizar ;; DB_PASSWORD) echo sintetica ;;
 esac
 return 0
}
pg_dump() {
 local -a files=("$DIR"/backups/*.tmp)
 [[ ${#files[@]} == 1 ]]
 stat -c '%a' "${files[0]}" >"$DIR/modo-temporal"
 stat -c '%a' "$DIR/backups" >"$DIR/modo-directorio"
 if [[ "$REAL" == 1 ]]; then "$PG_BIN/pg_dump" "$@"; return; fi
 [[ "$CASE" != dump-falla ]] || { printf 'parcial'; return 2; }
 [[ "$CASE" != vacio ]] || return 0
 printf 'archivo-sintetico'
}
pg_restore() {
 if [[ "$REAL" == 1 ]]; then
   if [[ "$1" == --file=/dev/null && "$WINDOWS" == 1 ]]; then shift; "$PG_BIN/pg_restore" --file=NUL "$@"
   else "$PG_BIN/pg_restore" "$@"; fi
   return
 fi
 [[ "$CASE" != toc-falla || "$1" != --list ]] || return 3
 [[ "$CASE" != datos-corruptos || "$1" == --list ]] || return 4
 return 0
}
'''
        script.write_text(harness + '\n' + function + '\nbackup_before_migration abcdef123456\nprintf "%s\\n" "$LAST_BACKUP" >"$DIR/respaldo-elegido"\ntouch "$DIR/continuo-despliegue"\n', 'utf-8', newline='\n')
        case_env = env | {'DIR': directory.as_posix(), 'ENVIRONMENT': environment, 'KEEP_PREDEPLOY_BACKUPS': keep,
                          'CASE': case, 'PORT': str(port), 'MISSING_USER': str(int(missing_user)),
                          'MISSING_PASSWORD': str(int(missing_password)), 'REAL': str(int(real)),
                          'WINDOWS': str(int(os.name == 'nt')), 'PG_BIN': args.pg_bin.as_posix()}
        p = subprocess.run([bash, str(script)], capture_output=True, env=case_env, creationflags=flags, timeout=45)
        return p, directory

    try:
        run([bash, '-n', str(repo / 'deploy/adrian/server/tramites-ctl')])
        check('sintaxis Bash del controlador', True)
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
        assert port != 5432
        if args.solo_mocks:
            assert os.name != 'nt', 'Los modos POSIX requieren Linux'
            p, d = shell('positivo-linux')
            check('funcion real con mocks permite continuar', p.returncode == 0)
            check('temporal600 desde creacion con umask022', (d / 'modo-temporal').read_text().strip() == '600')
            check('directorio700 antes de pg_dump', (d / 'modo-directorio').read_text().strip() == '700')
            chosen = Path((d / 'respaldo-elegido').read_text().strip())
            check('dump y huella publicados600', stat.S_IMODE(chosen.stat().st_mode) == 0o600
                  and stat.S_IMODE(chosen.with_name(chosen.name + '.sha256').stat().st_mode) == 0o600)
            for case in ['dump-falla', 'vacio', 'toc-falla', 'datos-corruptos']:
                p, d = shell(case)
                check(case + ': no continua ni publica', p.returncode != 0
                      and not (d / 'continuo-despliegue').exists() and not list(d.glob('backups/*.dump')))
            d = work / 'enlace con espacios'; d.mkdir(); (d / 'backups').symlink_to(work, target_is_directory=True)
            p, d = shell('enlace')
            check('directorio enlazado rechazado', p.returncode != 0 and not (d / 'continuo-despliegue').exists())
            result.update(ok=True, solo_mocks=True, permisos_posix_verificados=True)
            return
        check('binario PG18', b'PostgreSQL) 18.' in run([binary('postgres'), '--version']).stdout)
        data = work / 'data'
        run([binary('initdb'), '-D', data, '-U', 'admin_fixture', '--auth=trust', '--encoding=UTF8', '--no-locale'])
        with (data / 'postgresql.conf').open('a', encoding='utf-8') as f:
            f.write(f"\nlisten_addresses='127.0.0.1'\nport={port}\n")
        subprocess.run([binary('pg_ctl'), '-D', str(data), '-l', str(work / 'pg.log'), '-w', 'start'],
                       env=env, creationflags=flags, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, check=True, timeout=45)
        started = True

        def sql(q, db='postgres', user='admin_fixture'):
            return run([binary('psql'), '-XqAtw', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1',
                        '-p', port, '-U', user, '-d', db], q.encode()).stdout.decode().strip()

        check('directorio nuevo confirmado antes de DDL', Path(sql('SHOW data_directory')).resolve() == data.resolve())
        sql('CREATE ROLE tramites_prod_owner NOLOGIN; CREATE ROLE migrador_fixture LOGIN; '
            'GRANT tramites_prod_owner TO migrador_fixture WITH INHERIT FALSE, SET TRUE;')
        sql('CREATE DATABASE fixture OWNER tramites_prod_owner')
        sql("SET ROLE tramites_prod_owner; CREATE SCHEMA tramites AUTHORIZATION tramites_prod_owner; "
            "CREATE TABLE tramites.dato(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, valor text); "
            "INSERT INTO tramites.dato(valor) VALUES('sintetico'); GRANT SELECT ON tramites.dato TO migrador_fixture;", 'fixture')
        p, d = shell('positivo')
        check('respaldo positivo permite continuar', p.returncode == 0 and (d / 'continuo-despliegue').exists())
        result['permisos_posix_verificados'] = os.name != 'nt'
        if os.name != 'nt':
            check('temporal privado desde su creacion aun con umask022', (d / 'modo-temporal').read_text().strip() == '600')
            check('directorio privado antes de pg_dump', (d / 'modo-directorio').read_text().strip() == '700')
        chosen = Path((d / 'respaldo-elegido').read_text().strip())
        # Git Bash escribe rutas C:/... recibidas por DIR; Path acepta ambas plataformas.
        check('huella coincide con archivo publicado', chosen.with_name(chosen.name + '.sha256').read_text().split()[0]
              == hashlib.sha256(chosen.read_bytes()).hexdigest())
        check('no queda temporal tras exito', not list((d / 'backups').glob('*.tmp')))
        for case, kwargs in [('dump-falla', {}), ('vacio', {}), ('toc-falla', {}), ('datos-corruptos', {}),
                             ('sin-usuario', {'missing_user': True}), ('sin-clave', {'missing_password': True}),
                             ('retencion-cero', {'keep': '0'}), ('retencion-negativa', {'keep': '-1'}),
                             ('retencion-no-numero', {'keep': 'x'}), ('retencion-excesiva', {'keep': '10000'})]:
            p, d = shell(case, **kwargs)
            check(case + ': falla cerrado y no continua ni publica dump', p.returncode != 0
                  and not (d / 'continuo-despliegue').exists() and not list(d.glob('backups/*.dump')))
        d = work / 'retencion con espacios'; (d / 'backups').mkdir(parents=True)
        for i in range(12):
            f = d / 'backups' / f'pre-deploy-20000101T0000{i:02}Z-abcdef.dump'
            f.write_bytes(b'anterior'); f.with_name(f.name + '.sha256').write_text('anterior')
        p, d = shell('retencion')
        check('retencion predeterminada conserva diez dumps y sus diez huellas', p.returncode == 0
              and len(list((d / 'backups').glob('*.dump'))) == 10
              and len(list((d / 'backups').glob('*.dump.sha256'))) == 10)
        d = work / 'retencion-uno con espacios'; (d / 'backups').mkdir(parents=True)
        old = d / 'backups/pre-deploy-99991231T235959Z-abcdef.dump'; old.write_bytes(b'anterior')
        p, d = shell('retencion-uno', keep='1')
        selected = Path((d / 'respaldo-elegido').read_text().strip())
        check('retencion uno conserva el nuevo incluso con nombre anterior futuro', p.returncode == 0
              and selected.exists() and len(list((d / 'backups').glob('*.dump'))) == 1 and not old.exists())
        p, d = shell('staging', environment='staging')
        check('staging no ejecuta respaldo ni crea directorio', p.returncode == 0 and not (d / 'backups').exists())
        p, d = shell('real', real=True)
        check('pg_dump real y lectura integral con pg_restore', p.returncode == 0)
        dump = Path((d / 'respaldo-elegido').read_text().strip())
        sql('CREATE DATABASE restauracion_fixture OWNER tramites_prod_owner')
        run([binary('pg_restore'), '--exit-on-error', '-h', '127.0.0.1', '-p', port,
             '-U', 'admin_fixture', '-d', 'restauracion_fixture', dump])
        for label, query in [
            ('datos', 'SELECT id,valor FROM tramites.dato ORDER BY id'),
            ('propietario y ACL', "SELECT c.relname,pg_get_userbyid(c.relowner),c.relacl FROM pg_class c "
             "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='tramites' ORDER BY c.relname"),
            ('secuencia', 'SELECT last_value,is_called FROM tramites.dato_id_seq')]:
            check('restauracion aislada conserva ' + label, sql(query, 'fixture') == sql(query, 'restauracion_fixture'))
        corrupt = work / 'truncado.dump'; corrupt.write_bytes(dump.read_bytes()[:len(dump.read_bytes()) // 2])
        check('pg_restore real rechaza archivo truncado', run([binary('pg_restore'), '--file=' + str(work / 'truncado.sql'), corrupt], ok=False).returncode != 0)
        result['ok'] = True
    finally:
        if started:
            run([binary('pg_ctl'), '-D', work / 'data', '-m', 'fast', '-w', 'stop'])
        result.update(total=len(cases), casos=cases, cluster_detenido=not (work / 'data/postmaster.pid').exists())
        name = 'resultado-respaldo-permisos-linux.json' if args.solo_mocks else 'resultado-respaldo-produccion.json'
        output = args.output or work / name
        output.parent.mkdir(parents=True, exist_ok=True)
        result['evidencia_json'] = str(output)
        output.write_text(json.dumps(result, indent=2), 'utf-8')
        print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
