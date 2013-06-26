#!/usr/bin/env python

"Start all Myria workers in the specified deployment."

import myriadeploy
import setup_cluster

import subprocess
import sys

def start_workers(config):
    "Start all Myria workers in the specified deployment."
    description = config['description']
    default_path = config['path']
    workers = config['workers']
    username = config['username']
    max_heap_size = config['max_heap_size']

    worker_id = 0
    for worker in workers:
        worker_id = worker_id + 1
        (hostname, port, path) = setup_cluster.get_host_port_path(worker, default_path)
        cmd = "cd %s/%s-files; nohup java -cp 'libs/*' -Djava.util.logging.config.file=conf/logging.properties -Dlog4j.configuration=log4j.properties -Djava.library.path=sqlite4java-282 " % (path, description) + max_heap_size + " edu.washington.escience.myriad.parallel.Worker --workingDir %s/worker_%d 0</dev/null 1>worker_%d_stdout 2>worker_%d_stderr &" % (description, worker_id, worker_id, worker_id)
        args = ["ssh", "%s@%s" % (username, hostname), cmd]
        if subprocess.call(args):
            print >> sys.stderr, "error starting worker %s" % (hostname)
        print hostname

def main(argv):
    "Start all Myria workers in the specified deployment."
    # Usage
    if len(argv) != 2:
        print >> sys.stderr, "Usage: %s <deployment.cfg>" % (argv[0])
        print >> sys.stderr, "       deployment.cfg: a configuration file modeled after deployment.cfg.sample"
        sys.exit(1)

    # Parse the configuration
    config = myriadeploy.read_config_file(argv[1])

    # Start the workers
    start_workers(config)

if __name__ == "__main__":
    main(sys.argv)