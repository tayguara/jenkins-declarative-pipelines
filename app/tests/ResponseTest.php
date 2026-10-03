<?php

declare(strict_types=1);

namespace App\Tests;

use App\Http\Response;
use PHPUnit\Framework\Attributes\CoversClass;
use PHPUnit\Framework\TestCase;

#[CoversClass(Response::class)]
final class ResponseTest extends TestCase
{
    public function testTextDefaultsToOkPlainText(): void
    {
        $response = Response::text('hello');

        self::assertSame(200, $response->status);
        self::assertSame('hello', $response->body);
        self::assertSame(['Content-Type' => 'text/plain; charset=utf-8'], $response->headers);
    }

    public function testTextAcceptsAStatus(): void
    {
        self::assertSame(404, Response::text('Not found', 404)->status);
    }

    public function testJsonEncodesTheData(): void
    {
        $response = Response::json(['a' => 'b/c']);

        self::assertSame(200, $response->status);
        self::assertSame('{"a":"b\/c"}', $response->body);
        self::assertSame(['Content-Type' => 'application/json'], $response->headers);
    }

    public function testJsonSubstitutesInvalidUtf8InsteadOfThrowing(): void
    {
        self::assertSame('{"a":"x\ufffdy"}', Response::json(['a' => "x\xffy"])->body);
    }

    public function testJsonAcceptsAStatus(): void
    {
        self::assertSame(400, Response::json(['error' => 'x'], 400)->status);
    }

    public function testBareResponseHasNoBodyOrHeaders(): void
    {
        $response = new Response(401);

        self::assertSame('', $response->body);
        self::assertSame([], $response->headers);
    }
}
