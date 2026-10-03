<?php

declare(strict_types=1);

namespace App\Tests;

use App\ReleaseInfo;
use PHPUnit\Framework\Attributes\CoversClass;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

#[CoversClass(ReleaseInfo::class)]
final class ReleaseInfoTest extends TestCase
{
    private string $root;

    protected function setUp(): void
    {
        $this->root = sys_get_temp_dir() . '/release-info-' . bin2hex(random_bytes(6));
        mkdir($this->root);
    }

    protected function tearDown(): void
    {
        @unlink($this->root . '/RELEASE');
        @rmdir($this->root);
    }

    public function testReadsVersionAndBuildFromReleaseFile(): void
    {
        file_put_contents($this->root . '/RELEASE', '{"version":"abc1234","build":"12"}');

        $info = ReleaseInfo::fromReleaseRoot($this->root, 'green');

        self::assertSame('abc1234', $info->version);
        self::assertSame('12', $info->build);
        self::assertSame('green', $info->slot);
    }

    public function testFallsBackToDevWhenReleaseFileIsMissing(): void
    {
        $info = ReleaseInfo::fromReleaseRoot($this->root, 'blue');

        self::assertSame('dev', $info->version);
        self::assertSame('0', $info->build);
        self::assertSame('blue', $info->slot);
    }

    /**
     * @return iterable<string, array{string}>
     */
    public static function unusableReleaseFiles(): iterable
    {
        yield 'not json' => ['not json'];
        yield 'json scalar' => ['"abc"'];
        yield 'missing keys' => ['{}'];
        yield 'wrong types' => ['{"version":1,"build":2}'];
        yield 'empty version' => ['{"version":"","build":"3"}'];
    }

    #[DataProvider('unusableReleaseFiles')]
    public function testFallsBackToDevWhenReleaseFileIsUnusable(string $contents): void
    {
        file_put_contents($this->root . '/RELEASE', $contents);

        $info = ReleaseInfo::fromReleaseRoot($this->root, 'blue');

        self::assertSame('dev', $info->version);
        self::assertSame('0', $info->build);
    }
}
