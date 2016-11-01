#!/bin/bash
DIR=`dirname $0`
set -x

oc login -u test -p test --insecure-skip-tls-verify=true https://localhost:8443
oc delete project enmasse-ci

oc new-project enmasse-ci
oc policy add-role-to-user view system:serviceaccount:$(oc project -q):default
oc policy add-role-to-user edit system:serviceaccount:$(oc project -q):deployer

oc process -f https://raw.githubusercontent.com/enmasseproject/openshift-configuration/master/generated/enmasse-template.yaml | oc create -f -

$DIR/wait_until_up.sh 7 || exit 1

sleep 300

OPENSHIFT_NAMESPACE="enmasse-ci" OPENSHIFT_USER=test OPENSHIFT_TOKEN=`oc config view -o jsonpath='{.users[?(@.name == "test/localhost:8443")].user.token}'` OPENSHIFT_URL=https://localhost:8443 gradle check -i
