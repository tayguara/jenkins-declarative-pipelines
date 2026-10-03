<?php

declare(strict_types=1);

namespace App\Tests;

use App\HealthCheck;
use App\Http\Request;
use App\ReleaseInfo;
use PHPUnit\Framework\Attributes\CoversClass;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

#[CoversClass(HealthCheck::class)]
final class HealthCheckTest extends TestCase
{
    private const string TOKEN = 'correct-horse-battery-staple';

    private function request(?string $token): Request
    {
        return new Request('GET', '/health', [], $token === null ? [] : ['x-deploy-token' => $token]);
    }

    private function check(?string $configuredToken): HealthCheck
    {
        return new HealthCheck(new ReleaseInfo('abc1234', '12', 'blue'), static fn(): ?string => $configuredToken);
    }

    public function testReportsReleaseWhenTokenMatches(): void
    {
        $response = $this->check(self::TOKEN)->handle($this->request(self::TOKEN));

        self::assertSame(200, $response->status);
        self::assertSame('application/json', $response->headers['Content-Type']);
        self::assertSame('{"status":"ok","slot":"blue","version":"abc1234","build":"12"}', $response->body);
    }

    public function testRejectsWrongToken(): void
    {
        $response = $this->check(self::TOKEN)->handle($this->request('wrong-token'));

        self::assertSame(401, $response->status);
        self::assertSame('', $response->body);
    }

    public function testRejectsMissingToken(): void
    {
        $response = $this->check(self::TOKEN)->handle($this->request(null));

        self::assertSame(401, $response->status);
        self::assertSame('', $response->body);
    }

    /**
     * @return iterable<string, array{?string, ?string}>
     */
    public static function emptyTokenCombinations(): iterable
    {
        yield 'nothing configured, nothing sent' => [null, null];
        yield 'nothing configured, empty sent' => [null, ''];
        yield 'empty configured, empty sent' => ['', ''];
        yield 'empty configured, token sent' => ['', 'anything'];
        yield 'nothing configured, token sent' => [null, 'anything'];
    }

    #[DataProvider('emptyTokenCombinations')]
    public function testDeniesWhenNoTokenIsConfigured(?string $configured, ?string $presented): void
    {
        $response = $this->check($configured)->handle($this->request($presented));

        self::assertSame(401, $response->status);
        self::assertSame('', $response->body);
    }

    public function testDoesNotCallTheTokenProviderTwice(): void
    {
        $calls = 0;
        $check = new HealthCheck(
            new ReleaseInfo('abc1234', '12', 'blue'),
            static function () use (&$calls): string {
                ++$calls;

                return self::TOKEN;
            },
        );

        $check->handle($this->request(self::TOKEN));

        self::assertSame(1, $calls);
    }
}
