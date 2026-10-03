<?php

declare(strict_types=1);

namespace App;

use App\Http\Request;
use App\Http\Response;
use Closure;
use InvalidArgumentException;

/**
 * Routes a request to a response. Pure: no globals, no output, no files.
 */
final readonly class Kernel
{
    private HealthCheck $health;

    /**
     * @param Closure(): ?string $tokenProvider returns the expected deploy token, or null when none is configured
     */
    public function __construct(
        private ReleaseInfo $release,
        Closure $tokenProvider,
    ) {
        $this->health = new HealthCheck($release, $tokenProvider);
    }

    public function handle(Request $request): Response
    {
        $route = match ($request->path) {
            '/' => fn(): Response => Response::text(sprintf(
                'release %s (build %s) on %s',
                $this->release->version,
                $this->release->build,
                $this->release->slot,
            )),
            '/health' => fn(): Response => $this->health->handle($request),
            '/api/next-version' => fn(): Response => $this->nextVersion($request),
            default => null,
        };

        if ($route === null) {
            return Response::text('Not found', 404);
        }

        if ($request->method !== 'GET') {
            return new Response(405, '', ['Allow' => 'GET']);
        }

        return $route();
    }

    private function nextVersion(Request $request): Response
    {
        $current = $request->queryParam('current');
        $bump = $request->queryParam('bump');

        if ($current === null || $bump === null) {
            return Response::json(
                ['error' => sprintf('Missing query parameter "%s"', $current === null ? 'current' : 'bump')],
                400,
            );
        }

        try {
            return Response::json(['current' => $current, 'next' => SemverBump::bump($current, $bump)]);
        } catch (InvalidArgumentException $e) {
            return Response::json(['error' => $e->getMessage()], 400);
        }
    }
}
