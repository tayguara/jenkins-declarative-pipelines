<?php

declare(strict_types=1);

namespace App\Tests;

use App\Http\Request;
use App\Kernel;
use App\ReleaseInfo;
use PHPUnit\Framework\Attributes\CoversClass;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

#[CoversClass(Kernel::class)]
final class KernelTest extends TestCase
{
    private const string TOKEN = 'correct-horse-battery-staple';

    private function kernel(?string $token = self::TOKEN): Kernel
    {
        return new Kernel(new ReleaseInfo('abc1234', '12', 'blue'), static fn(): ?string => $token);
    }

    /**
     * @param array<string, string> $query
     */
    private function get(string $path, array $query = [], ?string $token = null): Request
    {
        return new Request('GET', $path, $query, $token === null ? [] : ['x-deploy-token' => $token]);
    }

    public function testRootDescribesTheRelease(): void
    {
        $response = $this->kernel()->handle($this->get('/'));

        self::assertSame(200, $response->status);
        self::assertSame('text/plain; charset=utf-8', $response->headers['Content-Type']);
        self::assertSame('release abc1234 (build 12) on blue', $response->body);
    }

    public function testHealthIsServedWithAValidToken(): void
    {
        $response = $this->kernel()->handle($this->get('/health', [], self::TOKEN));

        self::assertSame(200, $response->status);
        self::assertSame('{"status":"ok","slot":"blue","version":"abc1234","build":"12"}', $response->body);
    }

    public function testHealthIsDeniedWithoutAToken(): void
    {
        $response = $this->kernel()->handle($this->get('/health'));

        self::assertSame(401, $response->status);
        self::assertSame('', $response->body);
    }

    public function testHealthIsDeniedWhenNoTokenIsConfigured(): void
    {
        $response = $this->kernel(null)->handle($this->get('/health', [], ''));

        self::assertSame(401, $response->status);
    }

    public function testNextVersionBumpsTheRequestedPart(): void
    {
        $response = $this->kernel()->handle($this->get('/api/next-version', ['current' => '1.2.3', 'bump' => 'minor']));

        self::assertSame(200, $response->status);
        self::assertSame('application/json', $response->headers['Content-Type']);
        self::assertSame('{"current":"1.2.3","next":"1.3.0"}', $response->body);
    }

    /**
     * @return iterable<string, array{array<string, string>, string}>
     */
    public static function badNextVersionQueries(): iterable
    {
        yield 'no parameters' => [[], 'Missing query parameter "current"'];
        yield 'missing bump' => [['current' => '1.2.3'], 'Missing query parameter "bump"'];
        yield 'invalid version' => [['current' => '1.2', 'bump' => 'patch'], 'Invalid version "1.2": expected MAJOR.MINOR.PATCH'];
        yield 'invalid bump' => [['current' => '1.2.3', 'bump' => 'huge'], 'Invalid bump "huge": expected major, minor or patch'];
    }

    /**
     * @param array<string, string> $query
     */
    #[DataProvider('badNextVersionQueries')]
    public function testNextVersionRejectsBadInput(array $query, string $message): void
    {
        $response = $this->kernel()->handle($this->get('/api/next-version', $query));

        self::assertSame(400, $response->status);
        self::assertSame('application/json', $response->headers['Content-Type']);
        self::assertSame(json_encode(['error' => $message], JSON_THROW_ON_ERROR), $response->body);
    }

    public function testInvalidUtf8InputIsRejectedAsJsonNotAnException(): void
    {
        $response = $this->kernel()->handle($this->get('/api/next-version', ['current' => "1.2.\xff", 'bump' => 'minor']));

        self::assertSame(400, $response->status);
        self::assertSame('application/json', $response->headers['Content-Type']);
        self::assertSame(
            '{"error":"Invalid version \"1.2.\ufffd\": expected MAJOR.MINOR.PATCH"}',
            $response->body,
        );
    }

    public function testInvalidUtf8BumpIsRejectedAsJson(): void
    {
        $response = $this->kernel()->handle($this->get('/api/next-version', ['current' => '1.2.3', 'bump' => "\xff"]));

        self::assertSame(400, $response->status);
        self::assertStringContainsString('Invalid bump', $response->body);
    }

    public function testUnknownPathIsNotFound(): void
    {
        $response = $this->kernel()->handle($this->get('/nope'));

        self::assertSame(404, $response->status);
        self::assertSame('Not found', $response->body);
    }

    /**
     * @return iterable<string, array{string}>
     */
    public static function knownPaths(): iterable
    {
        yield 'root' => ['/'];
        yield 'health' => ['/health'];
        yield 'next version' => ['/api/next-version'];
    }

    #[DataProvider('knownPaths')]
    public function testOnlyGetIsAllowed(string $path): void
    {
        $response = $this->kernel()->handle(new Request('POST', $path));

        self::assertSame(405, $response->status);
        self::assertSame('GET', $response->headers['Allow']);
    }

    public function testUnknownPathWinsOverMethodCheck(): void
    {
        $response = $this->kernel()->handle(new Request('POST', '/nope'));

        self::assertSame(404, $response->status);
    }
}
