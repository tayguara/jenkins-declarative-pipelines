<?php

declare(strict_types=1);

/**
 * Router for the built-in PHP server: a tiny "load balancer" for the blue-green demo.
 *
 * - Without X-Slot it serves the live release: <releases>/current -> blue|green.
 * - With "X-Slot: blue|green" it serves that slot instead (preview of the idle
 *   slot, used by the pre-switch smoke check) and requires a valid X-Deploy-Token.
 *
 * The release path /srv/deploy/<env> below must match `releasesDir` in config/environments/<env>.yaml:
 * the pipeline writes the releases there and this router reads them from the same shared volume.
 *
 * The symlink is resolved on every request (clearstatcache + realpath) so a switch
 * is visible immediately. In production the equivalent is reloading php-fpm.
 */

const SLOTS = ['blue', 'green'];

function respond(int $status, string $body = ''): never
{
    http_response_code($status);
    header('Content-Type: text/plain; charset=utf-8');
    echo $body;
    exit;
}

$env = getenv('APP_ENV');
if (!is_string($env) || preg_match('/^(staging|production)$/', $env) !== 1) {
    respond(500, "APP_ENV must be 'staging' or 'production'\n");
}

clearstatcache(true);

$slot = $_SERVER['HTTP_X_SLOT'] ?? null;
$target = '/srv/deploy/' . $env . '/current';

if ($slot !== null) {
    if (!in_array($slot, SLOTS, true)) {
        respond(400, "Invalid X-Slot: expected 'blue' or 'green'\n");
    }

    $tokenFile = getenv('DEPLOY_TOKEN_FILE');
    $expected = is_string($tokenFile) && is_readable($tokenFile)
        ? trim((string) file_get_contents($tokenFile))
        : '';
    $given = $_SERVER['HTTP_X_DEPLOY_TOKEN'] ?? '';

    // An empty expected token always denies, so a missing secret cannot open the preview.
    if ($expected === '' || !is_string($given) || !hash_equals($expected, $given)) {
        respond(401);
    }

    $target = '/srv/deploy/' . $env . '/' . $slot;
}

$root = realpath($target);
if ($root === false || !is_file($root . '/public/index.php')) {
    respond(503, "No release available yet\n");
}

$_SERVER['APP_SLOT'] = basename($root);
$_SERVER['SCRIPT_FILENAME'] = $root . '/public/index.php';
$_SERVER['SCRIPT_NAME'] = '/index.php';

chdir($root . '/public');
require $root . '/public/index.php';
