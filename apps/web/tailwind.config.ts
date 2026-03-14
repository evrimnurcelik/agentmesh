import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        mono: ["IBM Plex Mono", "monospace"],
        sans: ["DM Sans", "sans-serif"],
      },
      colors: {
        mesh: {
          green: "#63eba5",
          bg: "#0a0a0f",
          surface: "rgba(255,255,255,0.03)",
          border: "rgba(255,255,255,0.07)",
        },
      },
    },
  },
  plugins: [],
};

export default config;
