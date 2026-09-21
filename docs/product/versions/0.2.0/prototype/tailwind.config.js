/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        ink: {
          950: '#070b14',
          900: '#0b1220',
          800: '#121a2b',
          700: '#1a2438',
        },
        accent: {
          DEFAULT: '#22d3ee',
          dim: '#0891b2',
        },
      },
    },
  },
  corePlugins: {
    preflight: false,
  },
};
