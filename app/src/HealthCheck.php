<?php

declare(strict_types=1);

namespace App;

use App\Http\Request;
use App\Http\Response;
use Closure;

/**
 * Token-protected health endpoint. It never answers without a configured token.
 */
final readonly class HealthCheck
{
    /**
     * @param Closure(): ?string $tokenProvider returns the expected token, or null when none is configured
     */
    public function __construct(
        private ReleaseInfo $release,
        private Closure $tokenProvider,
    ) {}

    public function handle(Request $request): Response
    {
        $expected = ($this->tokenProvider)();
        $presented = $request->header('X-Deploy-Token');

        if ($expected === null || $expected === '' || $presented === null || !hash_equals($expected, $presented)) {
            return new Response(401);
        }

        return Response::json([
            'status' => 'ok',
            'slot' => $this->release->slot,
            'version' => $this->release->version,
            'build' => $this->release->build,
        ]);
    }
}
