import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

// --- Dağıtım hedefine göre URL / baseUrl ---
// GitLab Pages: http://lumix.pages.gitlab.hsoylu.dev/documentation/  (alt yoldan sunulur)
// Vercel:       https://<proje>.vercel.app/                          (kökten sunulur)
// Vercel, build ortamında VERCEL=1 değişkenini otomatik set eder → elle ayar gerekmez.
// (İstersen BASE_URL / SITE_URL env'leriyle her ortamda elle de ezebilirsin.)
const isVercel = Boolean(process.env.VERCEL);
const vercelUrl = process.env.VERCEL_PROJECT_PRODUCTION_URL || process.env.VERCEL_URL;

const config: Config = {
  title: 'Lumix Documentation',
  tagline: 'Documentation for Lumix',
  favicon: 'img/favicon.ico',

  // Future flags, see https://docusaurus.io/docs/api/docusaurus-config#future
  future: {
    v4: true, // Improve compatibility with the upcoming Docusaurus v4
  },

  // Set the production url of your site here
  // GitLab Pages standart (wildcard) kurulumda site şu yoldan sunulur:
  //   http://lumix.pages.gitlab.hsoylu.dev/documentation/
  // namespace_in_path=true kullanıyorsan baseUrl'i '/lumix/documentation/' yap.
  url: process.env.SITE_URL
    ? process.env.SITE_URL
    : isVercel
      ? `https://${vercelUrl ?? 'documentation-beryl-seven.vercel.app'}`
      : 'http://lumix.pages.gitlab.hsoylu.dev',
  // Set the /<baseUrl>/ pathname under which your site is served.
  // Vercel kökten (/), GitLab Pages alt yoldan (/documentation/) sunar.
  baseUrl: process.env.BASE_URL ?? (isVercel ? '/' : '/documentation/'),

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: 'LumixTech', // Usually your GitHub org/user name.
  projectName: 'documentation', // Usually your repo name.

  onBrokenLinks: 'throw',

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'tr'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          // Please change this to your repo.
          // Remove this to remove the "edit this page" links.
          editUrl:
            'https://github.com/LumixTech/documentation/tree/main/',
        },
        blog: {
          showReadingTime: true,
          feedOptions: {
            type: ['rss', 'atom'],
            xslt: true,
          },
          // Please change this to your repo.
          // Remove this to remove the "edit this page" links.
          editUrl:
            'https://github.com/LumixTech/documentation/tree/main/',
          // Useful options to enforce blogging best practices
          onInlineTags: 'warn',
          onInlineAuthors: 'warn',
          onUntruncatedBlogPosts: 'warn',
        },
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    // Replace with your project's social card
    image: 'img/docusaurus-social-card.jpg',
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Lumix Documentation',
      logo: {
        alt: 'Lumix Documentation Logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'tutorialSidebar',
          position: 'left',
          label: 'Docs',
        },
        {to: '/blog', label: 'Blog', position: 'left'},
        {
          type: 'localeDropdown',
          position: 'right',
        },
        {
          href: 'https://github.com/LumixTech/documentation',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {
              label: 'Docs',
              to: '/docs/intro',
            },
          ],
        },
        {
          title: 'Community',
          items: [
            {
              label: 'Stack Overflow',
              href: 'https://stackoverflow.com/questions/tagged/docusaurus',
            },
            {
              label: 'Discord',
              href: 'https://discordapp.com/invite/docusaurus',
            },
            {
              label: 'X',
              href: 'https://x.com/docusaurus',
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'Blog',
              to: '/blog',
            },
            {
              label: 'GitHub',
              href: 'https://github.com/LumixTech/documentation',
            },
          ],
        },
      ],
      copyright: `Copyright (c) ${new Date().getFullYear()} LumixTech. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
