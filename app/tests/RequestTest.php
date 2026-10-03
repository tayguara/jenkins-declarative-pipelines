<?php

declare(strict_types=1);

namespace App\Tests;

use App\Http\Request;
use PHPUnit\Framework\Attributes\CoversClass;
use PHPUnit\Framework\TestCase;

#[CoversClass(Request::class)]
final class RequestTest extends TestCase
{
    public function testBuildsFromServerGlobals(): void
    {
        $request = Request::fromGlobals(
            [
                'REQUEST_METHOD' => 'POST',
                'REQUEST_URI' => '/api/next-version?current=1.2.3',
                'HTTP_X_DEPLOY_TOKEN' => 'secret',
                'CONTENT_TYPE' => 'text/plain',
            ],
            ['current' => '1.2.3'],
        );

        self::assertSame('POST', $request->method);
        self::assertSame('/api/next-version', $request->path);
        self::assertSame('secret', $request->header('X-Deploy-Token'));
        self::assertNull($request->header('content-type'));
        self::assertSame('1.2.3', $request->queryParam('current'));
    }

    public function testDefaultsToGetOnRoot(): void
    {
        $request = Request::fromGlobals([], []);

        self::assertSame('GET', $request->method);
        self::assertSame('/', $request->path);
    }

    public function testFallsBackToRootWhenUriHasNoPath(): void
    {
        $request = Request::fromGlobals(['REQUEST_URI' => '//:80'], []);

        self::assertSame('/', $request->path);
    }

    public function testIgnoresNonStringHeaderValues(): void
    {
        $request = Request::fromGlobals(['HTTP_X_DEPLOY_TOKEN' => ['a']], []);

        self::assertNull($request->header('x-deploy-token'));
    }

    public function testQueryParamIsNullWhenMissingOrNotAString(): void
    {
        $request = Request::fromGlobals([], ['list' => ['a', 'b']]);

        self::assertNull($request->queryParam('missing'));
        self::assertNull($request->queryParam('list'));
    }
}
