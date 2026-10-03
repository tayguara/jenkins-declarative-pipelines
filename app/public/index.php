<?php

declare(strict_types=1);

use App\Http\Request;
use App\Kernel;
use App\ReleaseInfo;
use App\TokenFile;

require __DIR__ . '/../vendor/autoload.php';

// The router (deploy/web/router.php) sets APP_SLOT to the slot it resolved; locally it is unknown.
$slot = $_SERVER['APP_SLOT'] ?? 'local';
$tokenFile = getenv('DEPLOY_TOKEN_FILE');

$kernel = new Kernel(
    ReleaseInfo::fromReleaseRoot(dirname(__DIR__), is_string($slot) ? $slot : 'local'),
    static fn(): ?string => TokenFile::read($tokenFile === false ? null : $tokenFile),
);

$response = $kernel->handle(Request::fromGlobals($_SERVER, $_GET));

http_response_code($response->status);
foreach ($response->headers as $name => $value) {
    header($name . ': ' . $value);
}
echo $response->body;
